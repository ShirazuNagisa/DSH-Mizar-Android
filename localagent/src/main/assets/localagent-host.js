/**
 * The Android host for the Mizar local kernel — the JavaScript half of the
 * bridge in `Android/localagent`.
 *
 * The kernel is platform-neutral TypeScript (`iOS/LocalKernel/kernel`) and asks
 * its embedding for one object shaped like `PlatformHost` in
 * `kernel/src/host/host.ts`: a callback-style file system, a KV store, HTTP,
 * logging, a clock, timers. iOS builds that object in Swift
 * (`KernelRuntime.buildHostObject`); this file builds it for QuickJS.
 *
 * Why the wrapper, when the kernel already takes callbacks:
 *
 *  - The JNI bridge exposes exactly one function, `__mizarNativeCall(op, a, b)`,
 *    because crossing JNI per host method would mean one native stub and one
 *    method lookup per surface. Each host function here calls that one function
 *    synchronously with strings, then invokes the kernel's `cb` itself — the
 *    callback never crosses the bridge.
 *  - The bridge answers with one JSON envelope (`{ok, value}` / `{ok, error}`)
 *    because a `null` value, an array and an error would otherwise be
 *    indistinguishable through a string-only channel.
 *
 * Two surfaces are deliberately *not* the kernel's own:
 *
 *  - `http.request` cannot answer synchronously (a model stream lasts seconds to
 *    minutes). It starts the request on the Kotlin side and resolves later through
 *    `__mizarDrainHostEvents`, which the host runs on `__mizarPump`.
 *  - Connections are named: `__mizarOpenConnection(id)` / `__mizarSendOn(id, …)`
 *    let one engine serve the PoC's own client and a `MizarConnection` transport
 *    at the same time, each with its own frame stream.
 *
 * Everything here is deliberately plain ES5-ish JavaScript: QuickJS would take
 * ES2020, but this shim is the one file whose syntax error would take the whole
 * kernel down, so it stays boring on purpose.
 *
 * Globals the Kotlin side sets before evaluating this file:
 *   __mizarNativeCall  the JNI bridge function
 *   __mizarHostConfig  {hostName, home, defaultWorkspaceName, platformVersion}
 */
(function () {
  'use strict';

  var config = globalThis.__mizarHostConfig;
  var nativeCall = globalThis.__mizarNativeCall;

  // MARK: - Envelopes

  /** Call one bridge op and parse its envelope; never throws. */
  function envelope(op, first, second) {
    var raw;
    try {
      raw = nativeCall(
        op,
        first === undefined ? '' : String(first),
        second === undefined ? '' : String(second)
      );
    } catch (error) {
      return { ok: false, error: 'the host bridge threw: ' + String(error) };
    }
    try {
      return JSON.parse(String(raw));
    } catch (error) {
      return { ok: false, error: 'the host bridge returned malformed JSON: ' + String(error) };
    }
  }

  /**
   * Resolve the kernel's callback from one bridge envelope.
   *
   * `{ok: true, value: X}` -> `cb.ok(X)`; anything else -> `cb.fail({message})`,
   * which is the only failure shape the host API's `Callback<T>` knows.
   */
  function transfer(callback, op, first, second) {
    var parsed = envelope(op, first, second);
    if (parsed && parsed.ok === true) {
      callback.ok(parsed.value === undefined ? null : parsed.value);
      return;
    }
    callback.fail({ message: (parsed && parsed.error) || 'host bridge failure' });
  }

  /** The `value` of a bridge op, or [fallback] when it failed. */
  function valueOf(op, first, second, fallback) {
    var parsed = envelope(op, first, second);
    if (parsed && parsed.ok === true && parsed.value !== undefined) {
      return parsed.value;
    }
    return fallback;
  }

  // MARK: - Timers

  // The engine has no event loop of its own and the bridge exposes none either,
  // so timers live here: `setTimeout` only books a callback, and the host calls
  // `__mizarPump()` (each request round-trip does, plus a short idle tick) to run
  // the ones that are due. The contract the kernel relies on is timer ordering and
  // cancellation, not wall-clock fidelity.
  var timers = new Map();
  var nextTimerId = 0;

  // MARK: - Streaming HTTP

  // handle -> the handlers of one in-flight request. Chunks, the terminal status
  // and failures arrive out of band via `events.poll`; see __mizarDrainHostEvents.
  var requests = new Map();

  /**
   * Hand the JS side whatever finished on a host thread.
   *
   * The only asynchronous host surface is HTTP; its chunks are queued in Kotlin
   * and delivered here in order. A handler that throws is logged and skipped: one
   * broken consumer must not strand every other open request.
   */
  globalThis.__mizarDrainHostEvents = function () {
    var raw;
    try {
      raw = nativeCall('events.poll', '', '');
    } catch (error) {
      return 0;
    }
    var polled;
    try {
      polled = JSON.parse(String(raw));
    } catch (error) {
      return 0;
    }
    if (!polled || polled.ok !== true || typeof polled.value !== 'object' || polled.value === null) {
      return 0;
    }
    var events = polled.value;
    var count = 0;
    for (var index = 0; index < events.length; index += 1) {
      var event = events[index];
      var handle = String(event.handle);
      var handlers = requests.get(handle);
      if (!handlers) {
        continue;
      }
      try {
        if (event.op === 'chunk') {
          handlers.onChunk(String(event.text));
        } else if (event.op === 'done') {
          requests.delete(handle);
          handlers.onDone(Number(event.status));
        } else if (event.op === 'error') {
          requests.delete(handle);
          handlers.onError(String(event.message));
        }
      } catch (error) {
        envelope('log', 'error', 'a host event handler threw: ' + String(error));
      }
      count += 1;
    }
    return count;
  };

  // MARK: - The host object

  var host = {
    hostName: config.hostName,
    home: config.home,
    defaultWorkspaceName: config.defaultWorkspaceName,
    platformVersion: config.platformVersion,

    fs: {
      readText: function (path, callback) {
        transfer(callback, 'fs.readText', path);
      },
      writeText: function (path, data, callback) {
        transfer(callback, 'fs.writeText', path, data);
      },
      appendText: function (path, data, callback) {
        transfer(callback, 'fs.appendText', path, data);
      },
      deletePath: function (path, callback) {
        transfer(callback, 'fs.deletePath', path);
      },
      listDir: function (path, callback) {
        transfer(callback, 'fs.listDir', path);
      },
      isDirectory: function (path, callback) {
        transfer(callback, 'fs.isDirectory', path);
      },
      makeDir: function (path, callback) {
        transfer(callback, 'fs.makeDir', path);
      }
    },

    http: {
      /**
       * Start one streaming request.
       *
       * The kernel consumes `text/event-stream` line by line, so the host decodes
       * whole lines; a synchronous failure (a malformed URL, no bridge) is reported
       * through `onError` immediately, exactly as iOS does.
       */
      request: function (request, handlers) {
        var started;
        try {
          started = envelope('http.start', JSON.stringify({
            url: String(request && request.url ? request.url : ''),
            method: String(request && request.method ? request.method : 'GET'),
            headers: request && request.headers ? request.headers : {},
            body: request && typeof request.body === 'string' ? request.body : null
          }));
        } catch (error) {
          started = { ok: false, error: 'the host bridge threw: ' + String(error) };
        }
        if (!started || started.ok !== true) {
          handlers.onError(String((started && started.error) || 'the host could not start the request'));
          return {
            abort: function () {}
          };
        }
        var handle = String(started.value);
        requests.set(handle, handlers);
        return {
          abort: function () {
            envelope('http.abort', handle, '');
          }
        };
      }
    },

    kv: {
      get: function (key, callback) {
        transfer(callback, 'kv.get', key);
      },
      set: function (key, value, callback) {
        transfer(callback, 'kv.set', key, value);
      },
      delete: function (key, callback) {
        transfer(callback, 'kv.delete', key);
      }
    },

    log: function (level, message) {
      envelope('log', level, message);
    },

    uuid: function () {
      return String(valueOf('uuid', undefined, undefined, ''));
    },

    now: function () {
      return Number(valueOf('now', undefined, undefined, 0));
    },

    setTimeout: function (callback, milliseconds) {
      nextTimerId += 1;
      var id = nextTimerId;
      timers.set(id, {
        callback: callback,
        at: host.now() + (Number(milliseconds) || 0)
      });
      return id;
    },

    clearTimeout: function (handle) {
      timers.delete(handle);
    }
  };

  globalThis.__mizarHost = host;

  /**
   * Run host events and the timers that are due. Called by Kotlin after every
   * request frame and on a short idle tick; returns a number so an `evaluate` has a
   * primitive result to hand back.
   */
  globalThis.__mizarPump = function () {
    globalThis.__mizarDrainHostEvents();
    var now = host.now();
    var due = [];
    timers.forEach(function (timer, id) {
      if (timer.at <= now) {
        due.push(id);
      }
    });
    due.sort(function (left, right) {
      return left - right;
    });
    for (var index = 0; index < due.length; index += 1) {
      var id = due[index];
      var timer = timers.get(id);
      if (!timer) {
        continue;
      }
      timers.delete(id);
      try {
        timer.callback();
      } catch (error) {
        envelope('log', 'error', 'a timer callback threw: ' + String(error));
      }
    }
    return 1;
  };

  // MARK: - Connections

  // One JS-side connection per client. The kernel supports several at once; the
  // PoC's own default connection and a MizarConnection transport can coexist, and
  // each sees only its own frames.
  var connections = new Map();

  /**
   * Open a named connection and route its frames to Kotlin under that name.
   *
   * Returns the kernel's device id as a string (the same id every connection of
   * this kernel reports), or throws if the kernel is not booted.
   */
  globalThis.__mizarOpenConnection = function (connectionId) {
    var key = String(connectionId);
    var connection = globalThis.__mizarKernel.openConnection();
    connections.set(key, connection);
    connection.onFrame(function (text) {
      envelope('frame', key, String(text));
    });
    host.log('info', 'kernel connection ' + String(connection.deviceId) + ' opened as "' + key + '"');
    return String(connection.deviceId);
  };

  /** Send one already-encoded client frame on a named connection. */
  globalThis.__mizarSendOn = function (connectionId, frameText) {
    var connection = connections.get(String(connectionId));
    if (!connection) {
      return 0;
    }
    connection.send(String(frameText));
    return 1;
  };

  /** Close a named connection. Frames already queued in Kotlin still arrive. */
  globalThis.__mizarCloseConnection = function (connectionId) {
    var key = String(connectionId);
    var connection = connections.get(key);
    if (!connection) {
      return 0;
    }
    connections.delete(key);
    connection.close();
    return 1;
  };

  /**
   * Boot the kernel.
   *
   * Shaped exactly like iOS `KernelRuntime.bootSync`: `createKernel` is a promise
   * and the outcome is read from `globalThis.__mizarKernel` /
   * `globalThis.__mizarBootError`. Connections are opened separately, by name, so
   * this function only creates the kernel.
   */
  globalThis.__mizarBootKernel = function () {
    delete globalThis.__mizarBootError;
    return MizarKernel.createKernel({ host: globalThis.__mizarHost }).then(
      function (kernel) {
        globalThis.__mizarKernel = kernel;
        host.log('info', 'kernel booted');
        return 1;
      },
      function (error) {
        var message = error && error.message ? error.message : String(error);
        globalThis.__mizarBootError = message;
        host.log('error', 'kernel boot failed: ' + message);
        return 0;
      }
    );
  };
}());

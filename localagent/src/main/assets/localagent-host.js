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
  // `__mizarPump()` (each request round-trip does) to run the ones that are
  // due. The contract the kernel relies on is timer ordering and cancellation,
  // not wall-clock fidelity.
  var timers = new Map();
  var nextTimerId = 0;

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
      // A PoC host has no network surface yet: `http.request` is the streaming
      // engine/tool transport, and the first milestone only needs `kernel.*`
      // methods, which never touch it. Failing explicitly (rather than hanging)
      // keeps a turn that does try to use it honest.
      request: function (request, handlers) {
        var message = 'http.request is not implemented by the localagent PoC host';
        envelope('log', 'warn', message + ': ' + String(request && request.url));
        handlers.onError(message);
        return {
          abort: function () {}
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
   * Run the timers that are due. Called by Kotlin after every request frame;
   * returns a number so an `evaluate` has a primitive result to hand back.
   */
  globalThis.__mizarPump = function () {
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

  /**
   * Boot the kernel and open the one client connection the PoC uses.
   *
   * Shaped exactly like iOS `KernelRuntime.bootSync` / `openConnectionSync`:
   * `createKernel` is a promise, `openConnection` is not, and frames arrive
   * through `onFrame`. The returned promise exists so the bridge's job pump has
   * something to drive — Kotlin reads the outcome from
   * `globalThis.__mizarKernel` / `globalThis.__mizarBootError`.
   */
  globalThis.__mizarBootKernel = function () {
    delete globalThis.__mizarBootError;
    return MizarKernel.createKernel({ host: globalThis.__mizarHost }).then(
      function (kernel) {
        globalThis.__mizarKernel = kernel;
        var connection = kernel.openConnection();
        globalThis.__mizarConnection = connection;
        connection.onFrame(function (text) {
          envelope('frame', String(text));
        });
        host.log('info', 'kernel connection opened: ' + String(connection.deviceId));
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

  /** Send one already-encoded client frame. Returns 1 when a connection exists. */
  globalThis.__mizarSend = function (frameText) {
    var connection = globalThis.__mizarConnection;
    if (!connection) {
      return 0;
    }
    connection.send(String(frameText));
    return 1;
  };
}());

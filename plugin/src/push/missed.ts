/**
 * Completions that had nobody to hear them.
 *
 * A finished turn is announced on the socket, and that is the whole mechanism:
 * a connected client is told, and a client that was away when it happened was
 * meant to find the transition by polling the session list. That fallback has a
 * hole -- a turn that **starts and ends inside one outage** never produces a
 * transition the poll can see, because by the time the phone reconnects the row
 * has been idle the whole time the phone has existed. The completion is simply
 * gone.
 *
 * This is the small memory that closes it: an event broadcast to zero clients is
 * kept, and handed to the next client that connects. Three properties matter and
 * are why this is a type rather than an array next to the socket code:
 *
 *   - **Bounded.** A phone left off for a week must not make the host grow a
 *     queue per turn; the oldest are dropped first.
 *   - **Expiring.** A completion from this morning is not news by tonight. The
 *     window is long enough to cover "phone in a drawer overnight" and short
 *     enough that what arrives is still worth reading.
 *   - **Single delivery.** Taking empties the queue, so a reconnect cannot make
 *     the phone announce the same turn twice.
 *
 * A new turn in the same session discards that session's stale entry: the phone
 * would otherwise be told about work that has since been superseded.
 *
 * @module dsh-mizar/push/missed
 */

/** One completion nobody heard. */
export interface MissedCompletion {
  /** The session whose turn ended. */
  readonly sessionId: string
  /** Why it ended, as the host reported it, or `null`. */
  readonly reason: string | null
  /** When it was remembered, for expiry. */
  readonly at: number
}

/** How many completions to hold, and for how long. */
export interface MissedLimits {
  /** Most entries kept; the oldest are dropped first. */
  readonly limit: number
  /** How old an entry may be before it is forgotten, in milliseconds. */
  readonly ttlMs: number
}

/** Long enough for a phone left charging overnight; short enough to still matter. */
export const DEFAULT_MISSED_LIMITS: MissedLimits = { limit: 20, ttlMs: 12 * 60 * 60 * 1000 }

/** The completions awaiting a client, oldest first. */
export class MissedCompletions {
  private items: MissedCompletion[] = []

  /**
   * @param limits - how many to keep and for how long.
   */
  constructor(private readonly limits: MissedLimits = DEFAULT_MISSED_LIMITS) {}

  /** How many are waiting. */
  get size(): number {
    return this.items.length
  }

  /**
   * Keep one completion for the next client.
   *
   * @param completion - what happened, and when.
   */
  remember(completion: MissedCompletion): void {
    this.expire(completion.at)
    // The same session ending twice before anybody connects is two turns; the
    // phone should hear about the latest one, which is what it will open to.
    this.items = this.items.filter((item) => item.sessionId !== completion.sessionId)
    this.items.push(completion)
    while (this.items.length > this.limits.limit) this.items.shift()
  }

  /**
   * Forget everything remembered for a session, because it is running again.
   *
   * @param sessionId - the session that started a new turn.
   */
  forget(sessionId: string): void {
    this.items = this.items.filter((item) => item.sessionId !== sessionId)
  }

  /**
   * Take everything waiting, oldest first, and leave the queue empty.
   *
   * @param now - the current time, for expiry.
   */
  take(now: number = Date.now()): MissedCompletion[] {
    this.expire(now)
    const taken = this.items
    this.items = []
    return taken
  }

  /** Drop what is too old to be worth announcing. */
  private expire(now: number): void {
    this.items = this.items.filter((item) => now - item.at < this.limits.ttlMs)
  }
}
import { describe, expect, it } from 'vitest'
import { MissedCompletions } from '../src/push/missed.js'

/**
 * The queue exists for one case, and these tests are written around it: a turn
 * that starts and ends while the phone is away produces nothing the phone can
 * find afterwards. Everything else here is the property that keeps that fix from
 * becoming a new problem -- a queue that grows forever, delivers twice, or
 * announces yesterday's work as if it were new.
 */
describe('missed completions', () => {
  it('hands over what was kept, then forgets it', () => {
    const missed = new MissedCompletions()
    missed.remember({ sessionId: 'a', reason: 'completed', at: 1_000 })
    missed.remember({ sessionId: 'b', reason: null, at: 1_001 })

    const taken = missed.take(1_002)
    expect(taken.map((item) => item.sessionId)).toEqual(['a', 'b'])
    expect(taken[0]?.reason).toBe('completed')
    // A second connect must not announce the same turns again.
    expect(missed.take(1_003)).toEqual([])
    expect(missed.size).toBe(0)
  })

  it('delivers nothing when nothing happened', () => {
    expect(new MissedCompletions().take()).toEqual([])
  })

  it('drops what is too old to be news', () => {
    const missed = new MissedCompletions({ limit: 10, ttlMs: 1_000 })
    missed.remember({ sessionId: 'old', reason: null, at: 1_000 })
    missed.remember({ sessionId: 'new', reason: null, at: 3_000 })
    // The first is three seconds stale by the second's clock, the second is fresh.
    expect(missed.take(3_100)).toEqual([{ sessionId: 'new', reason: null, at: 3_000 }])
  })

  it('keeps the newest when more arrive than the limit allows', () => {
    const missed = new MissedCompletions({ limit: 2, ttlMs: 60_000 })
    for (const sessionId of ['a', 'b', 'c']) {
      missed.remember({ sessionId, reason: null, at: 1_000 })
    }
    expect(missed.take(1_000).map((item) => item.sessionId)).toEqual(['b', 'c'])
  })

  it('keeps one entry per session, describing the latest turn', () => {
    const missed = new MissedCompletions()
    missed.remember({ sessionId: 'a', reason: 'completed', at: 1_000 })
    missed.remember({ sessionId: 'a', reason: 'failed', at: 2_000 })
    expect(missed.take(2_000)).toEqual([{ sessionId: 'a', reason: 'failed', at: 2_000 }])
  })

  it('forgets a session that started a new turn', () => {
    const missed = new MissedCompletions()
    missed.remember({ sessionId: 'a', reason: null, at: 1_000 })
    missed.remember({ sessionId: 'b', reason: null, at: 1_000 })
    missed.forget('a')
    expect(missed.take(1_000).map((item) => item.sessionId)).toEqual(['b'])
  })
})
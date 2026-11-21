# Safety

Read this before you install anything.

STRIDE replaces the software on a treadmill console and then drives the
machine. It sets the belt speed and moves the incline deck while somebody is
standing on it.

---

## What can hurt you

**The belt and deck can move without you touching anything.** A route
changes the incline on its own, a warm-up ramps the belt up when a workout
starts, and a structured session (this fork) changes **both speed and
incline** at every step until it ends.

**The incline deck moves under load** — flat to nine percent shifts real weight
while you're on it.

**Everything that was dangerous about the treadmill before still is.** The
motor, belt and deck are unchanged.

---

## What the software does about it

**The safety key is the emergency stop, not the software.** Pull it and the
hardware cuts the belt, independently of anything STRIDE is doing. STRIDE
notices and shows a screen, but it isn't what stopped the belt.

**STRIDE drives the incline. Outside a session it only ever suggests a
speed.** A route moves the deck, because the deck can't run away underneath
you. It will say "lift to 6.4 km/h" and leave the belt where it is.

**A session drives the belt speed too (fork-only, 2026-11-21).** You start it
with a tap on its START, knowing that is what it does. Speed changes are
always a ramp at 1 km/h per second, never a jump, so a 7 to 13 km/h rep takes
about six seconds to arrive. A press on speed or incline, on the screen or on
the console's own keys, wins: it shifts the session's target by that much
until the next step. Stepping speed down to a stop holds the belt stopped
until the next step. STOP, the safety key and END behave exactly as they do
anywhere else, and a session never spins a stopped belt back up on its own.

**Automatic stops ramp down** at 2 km/h per second. The first guided walk cut
the belt from 4.2 km/h to zero the moment the plan ended, which was enough to
catch someone off guard — at a run it would have put them on the floor. **The
STOP button is exempt and stops immediately**, because that is a person
deciding.

**The board's limits win.** Incline and speed ranges are read from the machine
at startup and every plan is clamped to them. Nothing in Settings can raise
them.

**On a route, incline moves gradually** — a percent every three seconds by
default, so a big jump takes about half a minute rather than landing under you
mid-stride. Settings offers Gentle, Normal and Quick rather than raw numbers.
A session sends each step's grade at once and the deck moves at its motor's
own pace, the same as the console's incline keys.

---

## What this isn't

STRIDE is not a medical device and is not certified as anything.

Bluetooth straps, and handlebar grips on machines that have them, are consumer
heart rate sensors. If a number looks alarming, believe your body and a doctor.

---

## What you're doing to your treadmill

- **Your warranty is almost certainly void.** Assume it is.
- **You lose iFit** — subscription workouts, the app, the account.
- **You can make the console unbootable.** Read the whole install guide before
  starting, not while stuck halfway.

**It's reversible.** A factory reset restores the manufacturer's software,
verified on the machine STRIDE was built against. You are replacing the screen
on the front, not the motor controller.

That is not the same as supported — nothing here is supported by anybody, and a
reset that worked on one console is not a promise about yours. But the
realistic worst case is "reset it and start again", not "you now own a broken
treadmill".

Don't do this to a treadmill you don't own.

---

## If something goes wrong

1. **Pull the safety key.**
2. Step off.
3. Switch off at the wall.

Both still work whatever the console is doing. STRIDE can't disable either.

To get the treadmill back as it was, factory reset from the console's recovery
and set up iFit again.

---

## Reporting a safety problem

Please open a
[security advisory](https://github.com/keranm/strideApp/security/advisories/new)
rather than a public issue, so it can be looked at before it is widely known.

Anything that moves the belt or deck in a way the person on it didn't expect is
a safety problem, not a bug.

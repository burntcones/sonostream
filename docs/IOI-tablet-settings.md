# Aux tablet — stop the app from being killed (do this on each IOI tablet)

**Why:** The logs show the Aux app is being shut down and restarted by Android several times a day (≈9 times in 24 h). Each restart is a short silence until it reconnects. These settings tell Android to leave Aux running. Takes ~2 minutes per tablet.

Exact wording varies a little by tablet brand (Samsung / Lenovo / generic). Find the closest match.

## 1. Battery — set Aux to "Unrestricted" (most important)
- **Settings → Apps → Aux → Battery** → choose **Unrestricted** (not "Optimized" or "Restricted").
- If you don't see it there: **Settings → Battery → Battery optimization → (All apps) → Aux → Don't optimize**.

## 2. Stop Android from "pausing" the app when unused
- **Settings → Apps → Aux →** look for **"Pause app activity if unused"** (or "Remove permissions if app unused") → turn it **OFF**.

## 3. Lock Aux in recent apps (so "clear all" won't close it)
- Open the **recent apps** view (the square button or swipe-up-and-hold).
- Find the Aux card, **tap and hold its icon → Lock** (Samsung shows a small padlock). On other brands look for a pin/lock option.

## 4. Allow auto-start / background (Xiaomi, Oppo, Vivo, Realme, some Lenovo)
- **Settings → Apps → Aux → Autostart / Auto-launch →** turn **ON**.
- Also any "Background activity" / "Allow background" toggle → **ON / Allowed**.

## 5. Keep the screen on (helps Android keep the app foregrounded)
- The app already keeps the screen on while open. Also set **Settings → Display → Screen timeout → longest available / Never**, and keep the tablet **plugged in**.

## After doing this
Leave it running for a day. We'll check the logs to confirm the restarts dropped. If they don't, the tablet may simply be low on memory and we'll look at lightening the app or using a different tablet.

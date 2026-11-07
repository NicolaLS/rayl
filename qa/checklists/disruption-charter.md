# Charter: disruptions on a real iPhone (30 minutes)

**Explore** payments while the app is interrupted or the connection changes, **with** your
iPhone, Rayl's wallet and one external wallet (NWC first, then Blink), **to discover** payments
that end wrong: paid twice, shown as failed although they went out, or stuck in "Paying".

Simulators and emulators can't show this: iOS suspends a backgrounded app after a few seconds
and drops its sockets, and real networks switch between Wi-Fi and mobile data. The automated
tests cover the rest (test project 2, item 7: lost replies, a restarted relay, the app killed
mid-payment, the NWC client rebuilt after backgrounding).

Before you start: about 1,000 sats in each wallet, a second wallet for invoices of 21–100 sat,
and the receiving wallet's history open on another device. Turn on Settings → Developer → Network
Link Conditioner (Xcode installs it on a device once it has been used for development).

## Ideas to try

| Area | Try |
|---|---|
| Background between payments | Pay, go to the home screen for 1, 5 and 30 minutes, come back, pay again at once. Each wallet. Does the first payment after returning hang or fail? |
| Background during a payment | Scan, and while "Paying" shows, go home; come back after 10 s and after 2 min. Lock the screen instead. |
| Network switch | Pay on Wi-Fi, walk out of range or turn Wi-Fi off, pay on mobile data without leaving the app. And the reverse. |
| Airplane mode | Turn it on while "Paying" shows; turn it off after 10 s and after 1 min. Then scan the same invoice again. |
| Slow network | Network Link Conditioner "Very Bad Network" and "100% Loss": pay; what does the app say, and when? |
| Interrupted app | An incoming call (from another phone) during a payment; Control Center and Notification Center pulled down during confirmation. |
| Killed | Swipe Rayl away during "Paying"; reopen; scan the same invoice. |

For every payment, compare what Rayl showed with the receiving wallet's history: amount, once,
and whether it arrived at all.

## Record

Write notes in `qa/releases/<version>/runs/<date>-disruption-session.md`: what you tried, what
Rayl showed, what arrived. Each wrong outcome is a Linear issue (`MOB`, label `Bug`): paid twice
or "failed" for a payment that arrived is S1; stuck in "Paying" for more than a minute is S2
(see MOB-9).

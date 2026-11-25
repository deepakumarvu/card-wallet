# Card Wallet

An Android app that tracks your credit cards and what you spend on them, and tells you which card to use for a payment, e.g. "₹1,000 at Zomato → Swiggy HDFC Ornge, ₹50 back".

**Your data stays on the phone.** The only network use is reading your own Gmail (read-only) for statements and card alerts, when you turn it on. There's no account, server or analytics; Google backup and phone-to-phone transfer are turned off, and backups are a zip file you save yourself.

## What it does

- **Best card**: type or pick a merchant and an amount; every card is ranked by what it pays back, counting caps already used this statement cycle. Flip **International** to include cards kept for foreign spends.
- **Spends**: this cycle's spend and cashback per card, a breakdown by category, and every spend by day. Filter to one card.
- **Cards**: a wallet-style stack. Tap to open a card in place, long-press and drag to reorder. Each card shows its rules and how much of each cap is left.
- **Bank SMS**: reads card spend messages on the phone and logs them on the right card by the last 4 digits. Messages it can't place wait for you, pre-filled, with the reason.
- **Backup / restore**: one zip with a CSV per table; opens in any spreadsheet app.
- **Activity log**: every change to cards, rules and spends, and what happened to each SMS (More → Activity log). Kept on the phone, newest 2,000 lines.

## How rewards are worked out

Each card has a **base rate** and any number of **rules**. Rates are "per ₹100" in the card's own unit:

| Card type | Base rate means | ₹ per point |
|---|---|---|
| Cashback | ₹ back per ₹100 (1 = 1%) | 1 |
| Points | points per ₹100 (5 per ₹200 → 2.5) | what a point is worth to you, e.g. 0.50 |

A rule matches a spend when the merchant name has a word starting with one of its **keywords** (`swiggy, zomato`) or the merchant is in its **category** (Dining, Online, Bills, Fuel, Jewellery…). Rules can have:

- **Cap per statement cycle**, in ₹ or points.
- **Cap tied to base**: cap = N × the base reward earned that cycle (Airtel Axis style). The app counts the whole cycle, as the bank does at statement time, and tells you how much more spend unlocks the full rate.
- **Minimum order**: e.g. only on spends of ₹100 or more.
- **0 rate**: these spends earn nothing (rent, fuel), whatever the base rate.

Card settings that change the maths:

- **Statement day**: caps reset the day after it. Blank = calendar month.
- **Earns per full ₹ block**: e.g. 200 → ₹1,150 earns as ₹1,000.
- **Only for international spends**: left out of Best card unless International is on.
- **Debit card**: spends tracked, never suggested.
- **Old card digits**: SMS from a replaced card land on the new one.

Spends in a cycle are replayed in time order, so each takes the best rule that had room at that moment.

The **starter list** (Cards → My cards list) has rules for a set of Indian cards as of Nov 2026. Banks change terms often and the app is offline, so check and edit them.

## SMS reading

Bank messages are parsed with `parser-core`, taken from [PennyWise](https://github.com/sarim2000/pennywiseai-tracker) (AGPL-3.0), which knows 58 Indian banks' formats. Only card spends are logged:

- Credit card spends match a card by its last 4 digits (or old digits).
- Debit card spends are logged only when the digits match a card, so bank-account UPI debits stay out.
- OTPs and declined, failed or unconfirmed transactions are ignored.
- Anything else from a bank that looks like a card spend waits on the Spends tab with its reason. When you add or change card digits, waiting messages are checked again.

## Build and install

Needs JDK 21 and the Android SDK (platform 37). With Homebrew:

```sh
brew install openjdk@21
brew install --cask android-commandlinetools
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

Then:

```sh
./gradlew :app:testDebugUnitTest            # app tests
./gradlew :parser-core:test                 # SMS parser tests
./gradlew :app:assembleRelease              # optimised build, signed with the local debug key
adb install -r --user 0 app/build/outputs/apk/release/app-release.apk
```

`--user 0` installs for the main profile only; without it, Samsung's Dual Apps makes a second copy of the app.

## Project layout

| Path | What's there |
|---|---|
| `app/.../Cashback.kt` | Reward maths: rules, caps, cycles, ranking, merchant search |
| `app/.../Sms.kt` | SMS → spend, waiting reasons, inbox import, live SMS receiver |
| `app/.../Db.kt` | Room database (cards, rules, spends, categories, waiting SMS, activity log) |
| `app/.../Backup.kt` | Zip of CSVs, read and write |
| `app/.../Starter.kt` | Starter cards, rules and merchant keywords |
| `app/.../Screens.kt`, `More.kt`, `Dialogs.kt` | Compose screens and forms |
| `app/.../Wallet.kt`, `Theme.kt` | Card faces, colours, charts, theme |
| `app/schemas/` | Database schema per version, for automatic upgrades |
| `parser-core/` | Bank SMS parsers from PennyWise |

## Statement PDFs

More → Statements → **Import PDF** reads a card statement on the phone: it finds the card by its last 4 digits, sets the card's statement day, and adds purchases that SMS missed (bill payments, refunds, fees and cashback lines are skipped; a purchase already logged within 3 days is not added twice). Locked PDFs are opened by trying the usual Indian bank password patterns built from your name, date of birth, PAN and mobile (More → Statements → PDF passwords), kept only in the app.

The statement reader (`app/.../statement/`: `Parser.kt`, `Pdf.kt`, `Passwords.kt`, `TxnKind.kt`) comes from [CardKeep](https://github.com/santdas36/CardKeep) (GPL-3.0). It knows HDFC, ICICI, Axis, SBI Card, Kotak, IDFC FIRST, Yes Bank and Amex layouts, with a generic reader for others.

## Gmail

More → Gmail → **Connect Gmail** signs in with Google for a read-only token (`gmail.readonly`), then reads mail over IMAP on the phone:

- statement PDFs from HDFC, ICICI and Axis statement addresses, and mail with a statement subject from your banks' domains (Federal Bank/Scapia and Indian Bank included), each run through the statement import above;
- HDFC card alert emails, logged like SMS (skipped when the SMS already logged the same spend).

The first sync looks back 6 months; later ones pick up from the last. The sign-in uses the Google Cloud project in `docs/google-oauth-android-client.json` (Android client: package `app.cardwallet` + the signing key's SHA-1; no secret). While that project's consent screen is in Testing, Google asks to sign in again about every 7 days.

The mail client and alert parser (`MailClient.kt`, `AlertParser.kt`, `AlertRules.kt`) also come from CardKeep.

## Licence

AGPL-3.0, because `parser-core` comes from PennyWise under AGPL-3.0. The statement reader from CardKeep is GPL-3.0, which may be combined with AGPL-3.0 code (GPLv3 section 13). See [LICENSE](LICENSE).

# SpendLens - Architecture Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                         INGESTION LAYER                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│   UPI Apps                     SMS (v2)           Statements   │
│  ├─ Google Pay                  ├─ HDFC              ├─ CSV          │
│  ├─ PhonePe                     ├─ SBI               ├─ PDF          │
│  ├─ Paytm                       ├─ ICICI             └─ XLS          │
│  └─ BHIM                        └─ Axis                              │
│      │                               │                    │           │
│      ▼                               ▼                    ▼           │
│  UpiNotificationListener    SmsReceiver (full)     StatementImport   │
│                                                                       │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      PARSING & NORMALIZATION                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  TemplateParser                                                      │
│  ├─ Route by package/sender                                          │
│  ├─ Regex extraction                                                 │
│  ├─ Currency detection (never assumes INR)                           │
│  └─ Body hash for dedupe                                             │
│                                                                       │
│  Outputs: RawTxn                                                     │
│  ├─ source: NOTIFICATION | SMS | STATEMENT | MANUAL                  │
│  ├─ amountMinor: Long (always positive)                              │
│  ├─ currency: String (ISO 4217, required)                            │
│  ├─ direction: DEBIT | CREDIT                                        │
│  ├─ counterpartyVpa: String?                                         │
│  ├─ counterpartyNameRaw: String?                                     │
│  ├─ rrn: String? (UPI reference number)                              │
│  ├─ accountTail: String?                                             │
│  └─ bodyHash: String (dedupe)                                        │
│                                                                       │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       FUSION ENGINE                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  TransactionFuser                                                    │
│  ├─ Match across sources:                                            │
│  │  ├─ Exact RRN → confidence 1.0                                    │
│  │  ├─ Amount + currency + direction ± 90s → 0.8                    │
│  │  └─ + matching account tail → 0.9                                │
│  │                                                                   │
│  └─ Merge fields (highest trust wins):                               │
│     ├─ Name: NOTIFICATION > STATEMENT > SMS                          │
│     ├─ RRN: SMS > STATEMENT > NOTIFICATION                           │
│     ├─ Account: SMS > STATEMENT                                      │
│     └─ Amount: STATEMENT > SMS > NOTIFICATION                        │
│                                                                       │
│  Output: Merged RawTxn with source_mask                              │
│                                                                       │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    RESOLUTION LADDER (6 rungs)                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  MerchantResolver                                                    │
│                                                                       │
│  1️⃣  User Rules (priority: highest, confidence: 1.0)                │
│     └─ Check vpa_rules table for exact/prefix/regex match            │
│                                                                       │
│  2️⃣  Notification Display Name (confidence: 0.9)                    │
│     └─ Use clean merchant name from UPI app notification             │
│                                                                       │
│  3️⃣  VPA Structure Parsing (confidence: 0.7-0.8)                    │
│     ├─ ^\d{10}@ → Person (phone number)                             │
│     ├─ paytmqr* → Paytm QR                                           │
│     ├─ bharatpe.* → BharatPe merchant                                │
│     ├─ q\d+@ybl → PhonePe QR                                         │
│     └─ *.rzp@ → Razorpay merchant                                    │
│                                                                       │
│  4️⃣  Merchant Directory (confidence: 0.95)                          │
│     └─ Signed rule pack lookup                                       │
│                                                                       │
│  5️⃣  Fuzzy Match (confidence: 0.85+)                                │
│     └─ Levenshtein against historical VPAs                           │
│                                                                       │
│  6️⃣  Raw VPA Fallback (confidence: 0.3)                             │
│     └─ Display cleaned VPA (NEVER "Unknown")                         │
│                                                                       │
│  Output: Resolution                                                  │
│  ├─ displayName: String                                              │
│  ├─ merchantId: String?                                              │
│  ├─ categoryId: String?                                              │
│  ├─ rung: Int (1-6)                                                  │
│  └─ confidence: Float (0.0-1.0)                                      │
│                                                                       │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      PERSISTENCE LAYER                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  SQLCipher Database (AES-256 page-level encryption)                  │
│                                                                       │
│  Tables:                                                             │
│  ├─ transactions (main ledger)                                       │
│  ├─ merchants (canonical names)                                      │
│  ├─ vpa_rules (user corrections)                                     │
│  ├─ categories (spending categories)                                 │
│  ├─ accounts (bank accounts)                                         │
│  ├─ splits (group spending)                                          │
│  ├─ budgets (spending limits)                                        │
│  └─ seen_hashes (deduplication)                                      │
│                                                                       │
│  Key Features:                                                       │
│  ├─ All money as INTEGER (minor units)                               │
│  ├─ Soft deletes (deleted_at)                                        │
│  ├─ Source mask (bitwise OR)                                         │
│  ├─ Flags (self_transfer, refund, reversal, etc.)                    │
│  └─ Proper indexes for performance                                   │
│                                                                       │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        EMISSION LAYER                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  1. Nudge Notification (real-time)                                   │
│     "₹250 → Swiggy · ₹1,840 today · ₹6,200 this week on food"      │
│                                                                       │
│  2. Foreground Service Notification (persistent)                     │
│     "Spent today: ₹1,840"                                           │
│                                                                       │
│  3. Compose UI (Jetpack Compose + Material3)                         │
│     ┌─────────────────────────────────────────┐                     │
│     │ TODAY                      SAT 26 JUL   │                     │
│     │ ₹1,840                                  │ ← Hero (Bricolage)   │
│     │ 14 taps · 4 merchants                   │                     │
│     │ ▍▎▍█▎▎█▍▎▎▍█▎▍ ← Tap Bar (signature!)   │                     │
│     │                                         │                     │
│     │ 09:12  Swiggy ········· ₹250           │ ← Dotted leaders    │
│     │ 10:40  9822014455@ybl ·· ₹80           │                     │
│     │        [Name this merchant]             │ ← Review chip       │
│     │ 13:22  Blinkit ········ ₹600           │                     │
│     │        Split 4 ways · ₹2,400 paid       │ ← Split indicator   │
│     │ 17:05  Chai stall ······ ₹20            │                     │
│     │                                         │                     │
│     │ ───────────────────────────────────     │                     │
│     │ FRI 25 JUL              ₹620           │ ← Collapsed day     │
│     │ 6 taps · 5 merchants                    │                     │
│     └─────────────────────────────────────────┘                     │
│                                                                       │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     EXPORT & BACKUP                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Storage Access Framework (NO INTERNET permission)                   │
│                                                                       │
│  ┌─────────────────────────────────────────────────┐                │
│  │ User Passphrase                                 │                │
│  │       │                                          │                │
│  │       ▼                                          │                │
│  │ Argon2id (m=64MiB, t=3, p=4)                   │                │
│  │       │                                          │                │
│  │       ▼                                          │                │
│  │     KEK ──wraps──▶ DEK ──encrypts──▶ Archive   │                │
│  │                         XChaCha20-Poly1305      │                │
│  │                              │                   │                │
│  │                              ▼                   │                │
│  │                    SAF CREATE_DOCUMENT           │                │
│  │                              │                   │                │
│  │                              ▼                   │                │
│  │               Google Drive / OneDrive / SD card  │                │
│  │         (User's cloud app handles network I/O)  │                │
│  └─────────────────────────────────────────────────┘                │
│                                                                       │
│  Archive Format: spendlens-backup-{ISO8601}.slb                      │
│  ├─ header (plaintext metadata)                                      │
│  └─ ciphertext                                                       │
│     ├─ manifest.json                                                 │
│     ├─ db.sqlite (full snapshot)                                     │
│     └─ rules/ (user rules)                                           │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘


═══════════════════════════════════════════════════════════════════════
                         DESIGN PRINCIPLES
═══════════════════════════════════════════════════════════════════════

 Money is always an integer (minor units) + ISO-4217 currency
 Never display "Unknown" — always show VPA or meaningful name  
 Never assume INR — currency must be explicitly parsed
 Merge fields from multiple sources, don't dedupe entire transactions
 User corrections become rules and apply retroactively
 No INTERNET permission — backups via SAF
 Open source (GPLv3) — verifiable privacy
 No third-party SDKs — no analytics, no tracking

═══════════════════════════════════════════════════════════════════════
                          UI SIGNATURE
═══════════════════════════════════════════════════════════════════════

 Tap Bar - Square-root height scaling
   One mark per transaction, frequency becomes visible

 Day Stream - Not a dashboard
   Organized by time, not category

 Receipt Grammar - Dotted leaders, tabular figures
   Professional, readable, scannable

 Semantic Color - Violet for splits only
   Restraint is the aesthetic

═══════════════════════════════════════════════════════════════════════
```

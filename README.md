# 🧰 Nuclr Redis Explorer

Browse Redis in [Nuclr Commander](https://nuclr.dev) as if it were a folder on your disk. 📂
Keys are grouped into folders at `:`, so `user:42:session` sits in `user` / `42`. The keys
you already use on files work on your data: Ctrl+Q to look at a value, F4 to edit it, F8 to
delete it. Made for debugging caches and queues. 🐛🔍

```text
Redis Explorer
├── 🔌 Local                       a saved connection
│   ├── 🗄️ db0                      12,408 keys
│   │   ├── 📁 session              3,002 keys
│   │   ├── 📁 user                 9,400 keys
│   │   │   └── 📁 42
│   │   │       ├── 🔤 name         string     5 B
│   │   │       └── #️⃣ profile      hash       4 fields   ⏳ 2h 5m
│   │   ├── 📜 jobs                 list       17 items
│   │   ├── 🎲 tags                 set        8 members
│   │   ├── 🏆 leaderboard          zset       250 members
│   │   └── 🌊 events               stream     1,204 entries
│   └── 🗄️ db1
└── 🔒 Staging                     read-only
```

## ⌨️ Keys

| Key | On a key | On a folder | On a connection |
|---|---|---|---|
| **Enter** | | 📂 open | 🔌 connect |
| **Ctrl+Q** | 👁️ Quick View | | |
| **F4** | ✏️ edit the value | | ⚙️ edit the connection |
| **F5** | 📤 copy the value to the other panel | | |
| **F7** | | | ➕ new connection |
| **F8** | 🗑️ delete | 🗑️ delete every key in it | ❌ forget the connection |

Right-click a key for **Copy Key Name** 📋, or a connection for **Disconnect** 🔌.

## ✨ Features

- 🔌 **Connections**: host and port, a password or an ACL user, TLS 🔐. Paste a
  `redis://` or `rediss://` URL into the host field and it is split into the form. 🪄
- 🗝️ **Passwords stay out of files**: they live in the OS credential store. If the server
  refuses a saved one, you are asked again straight away, and the refused one is never
  sent again.
- 📁 **Folders from key names**, one level at a time, read with `SCAN` (never `KEYS`), so a
  big server is not blocked. 🐢➡️🐇 The separator is set per connection. A listing stops after
  50,000 keys, says so, and shows counts from it as "50,000+ keys". ✋
- 👁️ **Quick View** for every type:
  - 🔤 strings as text, with JSON laid out over lines and binary shown as a hex dump 🧾
  - #️⃣ a hash's fields, 📜 a list's items, 🎲 a set's members
  - 🏆 a sorted set's members with their scores
  - 🌊 a stream's newest entries, with the time each was added 🕒
- ✏️ **F4 edits a value** in Commander's editor: a string as itself; a hash, list, set or
  sorted set as JSON. Every save goes straight back to Redis, keeping the TTL ⏳.
  - 🤝 Someone changed the key meanwhile? You are asked before it is overwritten.
  - 🩹 Broken JSON? You are told why, and you can fix it and save again.
  - 💾 A save that does not make it to the server is never lost: the file is kept, and
    you are told where.
  - 🌊 Streams are append-only logs, so they are viewed, not edited.
- 🗑️ **F8 deletes** keys, or whole folders, after a confirmation that never understates
  how many keys go. 🧮 Deletion uses `UNLINK` in batches of 1,000, so big values are freed
  in the background and a folder of millions of keys never fills memory. 🧹
- 🔒 **Read-only connections** hide F4 and F8, for poking at production without fear. 😌

🚧 Not supported yet: Redis Cluster (the plugin says so when you connect), Sentinel
discovery, SSH tunnels.

## 🔨 Build

```bash
mvn clean verify
```

✍️ `verify` signs the ZIP and needs the Nuclr signing key; `mvn clean package` builds an
unsigned one. 🚀 `deploy.bat` builds, signs and copies it into Commander's `plugins` folder.

🧪 To run the tests against a real server as well (they flush database 15):

```bash
docker run -d --rm -p 6390:6379 --name nuclr-redis-test redis:7
mvn test -Dredis.live=localhost:6390
```

## 📄 License

Apache License 2.0 ⚖️

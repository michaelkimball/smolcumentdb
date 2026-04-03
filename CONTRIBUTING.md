# Contributing to SmolcumentDB

Thanks for your interest in contributing!

---

## Prerequisites

- Java 11 or later
- Gradle (or use the included `./gradlew` wrapper - no installation needed)

---

## Building locally

```bash
# Compile and run all tests
./gradlew build

# Tests only
./gradlew test

# Install to local Maven repository (~/.m2)
./gradlew publishToMavenLocal
```

---

## Project layout

```
src/main/java/com/smolcumentdb/
├── api/           Public entry points - SmolcumentDB, SmolcumentDBBuilder
├── protocol/      Wire protocol codec - MongoMessageDecoder, MongoMessageEncoder
├── server/        Netty server bootstrap and channel handler
├── command/       One handler class per MongoDB command group
├── storage/       In-memory database/collection storage
├── query/         FilterEvaluator - MongoDB query operator engine
└── aggregation/   AggregationPipeline - pipeline stage execution

src/test/java/com/smolcumentdb/
└── MorphiaIntegrationTest.java   End-to-end tests using Morphia 2.x
```

---

## Architecture

```
SmolcumentDB
├── Wire Protocol Layer  (Netty TCP)
│   ├── MongoMessageDecoder   Frame bytes → MongoMessage (OP_MSG + legacy OP_QUERY)
│   └── MongoMessageEncoder   MongoResponse → OP_MSG bytes
├── Command Dispatch
│   └── CommandDispatcher     Routes command name → handler
├── Command Handlers
│   ├── HandshakeHandler      hello / isMaster / ping / buildInfo
│   ├── InsertHandler
│   ├── FindHandler           + server-side cursor registry
│   ├── UpdateHandler
│   ├── DeleteHandler
│   ├── AggregateHandler
│   ├── AdminHandler
│   └── GetMoreHandler
├── Storage Layer
│   ├── InMemoryStorage       ConcurrentHashMap<db, ConcurrentHashMap<coll, InMemoryCollection>>
│   └── InMemoryCollection    CopyOnWriteArrayList<BsonDocument> - thread-safe reads & writes
├── Query Engine
│   └── FilterEvaluator       Evaluates MongoDB filter documents against BsonDocuments
├── Aggregation Engine
│   └── AggregationPipeline   Executes pipeline stages in order
└── Public API
    ├── SmolcumentDB          start() / stop() / getConnectionString()
    └── SmolcumentDBBuilder   Fluent builder
```

The server advertises **wire version 17** (equivalent to MongoDB 6.0), satisfying the MongoDB Java Driver 4.x requirement of `maxWireVersion >= 6`.

### Key design decisions

- **Netty framing** - `MongoMessageDecoder` extends `LengthFieldBasedFrameDecoder` and overrides `getUnadjustedFrameLength` to read the 4-byte length field as little-endian (MongoDB wire protocol is little-endian throughout).
- **OP_MSG only** - after the initial handshake the driver upgrades to OP_MSG (opcode 2013). Legacy OP_QUERY is only decoded to handle the very first `isMaster` command.
- **Thread safety** - `InMemoryStorage` uses `ConcurrentHashMap`; writes to `InMemoryCollection` are `synchronized` for multi-step atomicity; reads use `CopyOnWriteArrayList` snapshots.
- **Cursor registry** - `FindHandler.CURSOR_REGISTRY` is a static `ConcurrentHashMap<Long, List<BsonDocument>>` shared with `AggregateHandler` and `GetMoreHandler`.
- **No enforced indexes** - `createIndexes` is acknowledged as a no-op; unique/sparse constraints are not checked.

---

## Known limitations

- **No persistence** - data lives only in JVM heap; stops with the JVM
- **No authentication** - auth commands (`saslStart`, `authenticate`) are silently accepted
- **No TLS**
- **No transactions**
- **Indexes not enforced** - unique/sparse constraints are ignored
- **Aggregation expressions** - only `$sum`, `$concat`, `$toUpper`, `$toLower` are implemented inside `$project`/`$addFields`

---

## Running the tests

```bash
./gradlew test
```

Tests start an embedded SmolcumentDB server on a random port, run Morphia 2.x operations against it, and stop the server on completion. Each test method resets storage via `db.getStorage().reset()` to stay isolated.

---

## Making a release

Releases are fully automated via the [GitHub Actions release pipeline](.github/workflows/release.yml).  
The pipeline triggers on any tag matching `v*.*.*`.

### Steps

1. Ensure all changes are committed and pushed to `main`.

2. Create and push a version tag (use [Semantic Versioning](https://semver.org/)):

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

3. The pipeline will automatically:
   - Build and run all tests (release is aborted if any test fails)
   - Publish `smolcumentdb-X.Y.Z.jar`, `-sources.jar`, and `-javadoc.jar` to [GitHub Packages](https://github.com/michaelkimball/smolcumentdb/packages)
   - Create a [GitHub Release](https://github.com/michaelkimball/smolcumentdb/releases) with auto-generated release notes and the JARs attached as assets

### Version naming convention

| Tag | When to use |
|---|---|
| `v1.0.0` | Stable release |
| `v1.1.0` | New backward-compatible feature |
| `v1.0.1` | Bug fix |
| `v2.0.0` | Breaking API change |

---

## Submitting changes

1. Fork the repository and create a branch from `main`.
2. Make your changes with focused commits.
3. Ensure `./gradlew build` passes.
4. Open a pull request against `main` with a clear description.

---

## Repository links

- **GitHub**: https://github.com/michaelkimball/smolcumentdb
- **Packages**: https://github.com/michaelkimball/smolcumentdb/packages
- **Releases**: https://github.com/michaelkimball/smolcumentdb/releases

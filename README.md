# SmolcumentDB

[![Release](https://img.shields.io/github/v/release/michaelkimball/smolcumentdb)](https://github.com/michaelkimball/smolcumentdb/releases)
[![GitHub Packages](https://img.shields.io/badge/GitHub%20Packages-maven-blue)](https://github.com/michaelkimball/smolcumentdb/packages)

An embedded, in-memory MongoDB-compatible document database for Java 11+.  
It implements the [MongoDB Wire Protocol](https://www.mongodb.com/docs/manual/reference/mongodb-wire-protocol/) over a local TCP socket using [Netty](https://netty.io/), so the standard **MongoDB Java Driver 4.x** and **[Morphia 2.x](https://morphia.dev/)** work against it without any modification - the same way [H2](https://h2database.com/) works for SQL.

---

## Use cases

- **Unit and integration testing** - start a fresh embedded instance per test class, isolated and fast
- **Lightweight embedded production use** - ship a self-contained app that doesn't need a MongoDB server

---

## Quick start

### Dependency

Packages are published to [GitHub Packages](https://github.com/michaelkimball/smolcumentdb/packages).

**Gradle**

```groovy
repositories {
    maven {
        url = uri('https://maven.pkg.github.com/michaelkimball/smolcumentdb')
        credentials {
            username = project.findProperty('gpr.user') ?: System.getenv('GITHUB_ACTOR')
            password = project.findProperty('gpr.key')  ?: System.getenv('GITHUB_TOKEN')
        }
    }
}

dependencies {
    testImplementation 'com.smolcumentdb:smolcumentdb:0.1.0'
}
```

**Maven**

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/michaelkimball/smolcumentdb</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.smolcumentdb</groupId>
  <artifactId>smolcumentdb</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

> GitHub Packages requires authentication. Add your token to `~/.gradle/gradle.properties`:
> ```
> gpr.user=YOUR_GITHUB_USERNAME
> gpr.key=YOUR_PERSONAL_ACCESS_TOKEN
> ```

### With the MongoDB Java Driver

```java
SmolcumentDB db = SmolcumentDB.start();

MongoClient client = MongoClients.create(db.getConnectionString());
MongoDatabase database = client.getDatabase("mydb");
MongoCollection<Document> users = database.getCollection("users");

users.insertOne(new Document("name", "Alice").append("age", 30));
Document found = users.find(Filters.eq("name", "Alice")).first();
System.out.println(found.getString("name")); // Alice

client.close();
db.stop();
```

### With Morphia 2.x

```java
SmolcumentDB db = SmolcumentDB.start();
MongoClient client = MongoClients.create(db.getConnectionString());
Datastore ds = Morphia.createDatastore(client, "mydb");

// Save an entity
User alice = ds.save(new User("Alice", 30));

// Query
User found = ds.find(User.class)
        .filter(Filters.eq("name", "Alice"))
        .first();

// Update
ds.find(User.class)
        .filter(Filters.eq("name", "Alice"))
        .update(UpdateOperators.set("age", 31))
        .execute();

// Delete
ds.find(User.class)
        .filter(Filters.eq("name", "Alice"))
        .delete();

client.close();
db.stop();
```

---

## Configuration

Use the fluent builder to configure the server before starting:

```java
SmolcumentDB db = SmolcumentDB.builder()
        .host("127.0.0.1")
        .port(0)        // 0 = pick a random free port (recommended for tests)
        .start();

System.out.println(db.getConnectionString()); // e.g. "mongodb://127.0.0.1:54321"
System.out.println(db.getPort());             // e.g. 54321
```

| Builder method | Default | Description |
|---|---|---|
| `.host(String)` | `"127.0.0.1"` | Bind address |
| `.port(int)` | `0` | TCP port; `0` picks a random free port |

---

## JUnit 5 pattern

```java
@TestInstance(Lifecycle.PER_CLASS)
class MyServiceTest {

    SmolcumentDB db;
    MongoClient  client;
    Datastore    ds;

    @BeforeAll
    void start() {
        db     = SmolcumentDB.start();
        client = MongoClients.create(db.getConnectionString());
        ds     = Morphia.createDatastore(client, "testdb");
    }

    @AfterAll
    void stop() {
        client.close();
        db.stop();
    }

    @AfterEach
    void reset() {
        // Wipe all data between tests
        db.getStorage().reset();
    }
}
```

---

## Supported features

### CRUD

| Operation | MongoDB command |
|---|---|
| Insert one / many | `insert` |
| Find with filter, sort, skip, limit, projection | `find` |
| Update one / many (with upsert) | `update` |
| Delete one / many | `delete` |
| Cursor pagination | `getMore` |

### Query filter operators

| Category | Operators |
|---|---|
| Comparison | `$eq` `$ne` `$gt` `$gte` `$lt` `$lte` `$in` `$nin` |
| Logical | `$and` `$or` `$nor` `$not` |
| Element | `$exists` `$type` |
| Evaluation | `$regex` (with `i`, `m`, `s`, `x` options) |
| Array | `$all` `$elemMatch` `$size` |
| Other | dot-notation field paths (e.g. `"address.city"`) |

### Update operators

`$set`, `$unset`, `$inc`, `$push`, `$pull`, `$addToSet`, `$rename`

Full document replacement (no `$` operators) and upsert are also supported.

### Aggregation pipeline stages

`$match`, `$sort`, `$limit`, `$skip`, `$project`, `$group`, `$count`, `$unwind`, `$addFields`

#### Accumulator expressions (inside `$group`)

`$sum`, `$avg`, `$min`, `$max`, `$first`, `$last`, `$push`, `$addToSet`, `$count`

#### Value expressions

`$sum`, `$concat`, `$toUpper`, `$toLower` (field references with `$fieldName`)

### Admin commands

`listDatabases`, `listCollections`, `drop`, `dropDatabase`, `create` (createCollection), `createIndexes` (acknowledged no-op), `count`, `killCursors`

---

## Limitations

- **No persistence** - data lives only in JVM heap memory
- **No authentication** - auth commands are silently accepted and no-oped
- **No TLS**
- **Indexes are acknowledged but not enforced** - unique/sparse constraints are not checked
- **Aggregation expressions** - only a subset of `$project`/`$addFields` expressions is implemented
- **Transactions** - not supported

---

## Building

Requires Java 11+ and Gradle (or use the included wrapper).

```bash
./gradlew build   # compile + test
./gradlew test    # tests only
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on building, releasing, and contributing.

---

## Repository

- **GitHub**: https://github.com/michaelkimball/smolcumentdb
- **Packages**: https://github.com/michaelkimball/smolcumentdb/packages
- **Releases**: https://github.com/michaelkimball/smolcumentdb/releases

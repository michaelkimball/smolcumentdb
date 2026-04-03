package com.smolcumentdb;

import com.smolcumentdb.api.SmolcumentDB;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import dev.morphia.annotations.*;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests using Morphia 2.x against SmolcumentDB.
 *
 * Each test method gets a fresh, isolated database name to avoid ordering issues.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MorphiaIntegrationTest {

    // -------------------------------------------------------------------------
    // Entity definitions
    // -------------------------------------------------------------------------

    @Entity("users")
    static class User {
        @Id ObjectId id;
        String name;
        int    age;
        String email;

        User() {}
        User(String name, int age, String email) {
            this.name = name; this.age = age; this.email = email;
        }
    }

    @Entity("products")
    static class Product {
        @Id ObjectId id;
        String category;
        String name;
        double price;

        Product() {}
        Product(String category, String name, double price) {
            this.category = category; this.name = name; this.price = price;
        }
    }

    // -------------------------------------------------------------------------
    // Shared server fixture (started once for all tests)
    // -------------------------------------------------------------------------

    private static SmolcumentDB db;
    private static MongoClient  mongoClient;

    @BeforeAll
    static void startServer() {
        db          = SmolcumentDB.start();
        mongoClient = MongoClients.create(db.getConnectionString());
    }

    @AfterAll
    static void stopServer() {
        if (mongoClient != null) mongoClient.close();
        if (db != null) db.stop();
    }

    @AfterEach
    void resetStorage() {
        // Wipe between tests so they are independent
        db.getStorage().reset();
    }

    private Datastore datastore(String dbName) {
        return Morphia.createDatastore(mongoClient, dbName);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void insertAndFindOne() {
        Datastore ds = datastore("test_insert");
        // mapPackage is not needed when the entity class is directly referenced

        User saved = ds.save(new User("Alice", 30, "alice@example.com"));
        assertNotNull(saved.id, "save() should assign an ObjectId");

        User found = ds.find(User.class)
                .filter(Filters.eq("name", "Alice"))
                .first();
        assertNotNull(found);
        assertEquals("Alice", found.name);
        assertEquals(30, found.age);
    }

    @Test
    @Order(2)
    void insertMany() {
        Datastore ds = datastore("test_insertmany");

        ds.save(List.of(
                new User("Bob",   25, "bob@example.com"),
                new User("Carol", 35, "carol@example.com"),
                new User("Dave",  28, "dave@example.com")
        ));

        long count = ds.find(User.class).count();
        assertEquals(3, count);
    }

    @Test
    @Order(3)
    void filterComparison() {
        Datastore ds = datastore("test_filter");

        ds.save(List.of(
                new User("Alice", 30, "alice@example.com"),
                new User("Bob",   22, "bob@example.com"),
                new User("Carol", 40, "carol@example.com")
        ));

        List<User> over28 = ds.find(User.class)
                .filter(Filters.gt("age", 28))
                .iterator().toList();
        assertEquals(2, over28.size());
        assertTrue(over28.stream().allMatch(u -> u.age > 28));
    }

    @Test
    @Order(4)
    void updateSet() {
        Datastore ds = datastore("test_update");

        User alice = ds.save(new User("Alice", 30, "alice@example.com"));

        ds.find(User.class)
                .filter(Filters.eq("name", "Alice"))
                .update(UpdateOperators.set("age", 31))
                .execute();

        User updated = ds.find(User.class)
                .filter(Filters.eq("name", "Alice"))
                .first();

        assertNotNull(updated);
        assertEquals(31, updated.age);
    }

    @Test
    @Order(5)
    void deleteOne() {
        Datastore ds = datastore("test_delete");

        ds.save(List.of(
                new User("Alice", 30, "alice@example.com"),
                new User("Bob",   25, "bob@example.com")
        ));

        ds.find(User.class)
                .filter(Filters.eq("name", "Alice"))
                .delete();

        long remaining = ds.find(User.class).count();
        assertEquals(1, remaining);

        User bob = ds.find(User.class).first();
        assertNotNull(bob);
        assertEquals("Bob", bob.name);
    }

    @Test
    @Order(6)
    void aggregateGroupAndCount() {
        Datastore ds = datastore("test_aggregate");

        ds.save(List.of(
                new Product("electronics", "Phone",  699.99),
                new Product("electronics", "Laptop", 1299.99),
                new Product("clothing",    "Shirt",   29.99),
                new Product("clothing",    "Jeans",   59.99),
                new Product("clothing",    "Jacket", 149.99)
        ));

        // Use raw MongoCollection for aggregate to avoid Morphia version nuances
        var collection = mongoClient
                .getDatabase("test_aggregate")
                .getCollection("products", org.bson.Document.class);

        var pipeline = List.of(
                org.bson.Document.parse("{ $group: { _id: '$category', total: { $sum: 1 } } }"),
                org.bson.Document.parse("{ $sort: { _id: 1 } }")
        );

        List<org.bson.Document> results = collection.aggregate(pipeline).into(new java.util.ArrayList<>());
        assertEquals(2, results.size());

        // Sort result by _id for determinism
        results.sort(java.util.Comparator.comparing(d -> d.getString("_id")));

        assertEquals("clothing",    results.get(0).getString("_id"));
        assertEquals(3, ((Number) results.get(0).get("total")).intValue());
        assertEquals("electronics", results.get(1).getString("_id"));
        assertEquals(2, ((Number) results.get(1).get("total")).intValue());
    }

    @Test
    @Order(7)
    void findWithProjection() {
        Datastore ds = datastore("test_projection");

        ds.save(List.of(
                new User("Alice", 30, "alice@example.com"),
                new User("Bob",   22, "bob@example.com")
        ));

        // Use raw driver for projection
        var col = mongoClient.getDatabase("test_projection")
                .getCollection("users", org.bson.Document.class);

        List<org.bson.Document> docs = col
                .find()
                .projection(new org.bson.Document("name", 1).append("_id", 0))
                .into(new java.util.ArrayList<>());

        assertEquals(2, docs.size());
        docs.forEach(d -> {
            assertNotNull(d.getString("name"));
            assertNull(d.get("age"));
            assertNull(d.get("_id"));
        });
    }

    @Test
    @Order(8)
    void findWithSort() {
        Datastore ds = datastore("test_sort");

        ds.save(List.of(
                new User("Charlie", 45, "c@example.com"),
                new User("Alice",   30, "a@example.com"),
                new User("Bob",     22, "b@example.com")
        ));

        List<User> sorted = ds.find(User.class)
                .iterator(new dev.morphia.query.FindOptions()
                        .sort(Sort.ascending("age")))
                .toList();

        assertEquals(3, sorted.size());
        assertEquals("Bob",     sorted.get(0).name);
        assertEquals("Alice",   sorted.get(1).name);
        assertEquals("Charlie", sorted.get(2).name);
    }

    @Test
    @Order(9)
    void findWithSkipAndLimit() {
        Datastore ds = datastore("test_pagination");

        for (int i = 1; i <= 10; i++) {
            ds.save(new User("User" + i, 20 + i, "user" + i + "@example.com"));
        }

        List<User> page = ds.find(User.class)
                .iterator(new dev.morphia.query.FindOptions()
                        .sort(Sort.ascending("age"))
                        .skip(3).limit(3))
                .toList();

        assertEquals(3, page.size());
    }

    @Test
    @Order(10)
    void regexFilter() {
        Datastore ds = datastore("test_regex");

        ds.save(List.of(
                new User("Alice",   30, "alice@example.com"),
                new User("Alicia",  28, "alicia@example.com"),
                new User("Bob",     25, "bob@example.com")
        ));

        List<User> matching = ds.find(User.class)
                .filter(Filters.regex("name", "^Ali"))
                .iterator().toList();

        assertEquals(2, matching.size());
        assertTrue(matching.stream().allMatch(u -> u.name.startsWith("Ali")));
    }
}

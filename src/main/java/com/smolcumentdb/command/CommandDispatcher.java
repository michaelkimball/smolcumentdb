package com.smolcumentdb.command;

import com.smolcumentdb.storage.InMemoryStorage;
import org.bson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes an incoming MongoDB command (extracted from an OP_MSG frame)
 * to the appropriate handler and returns the response document.
 */
public class CommandDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(CommandDispatcher.class);

    private final HandshakeHandler handshake;
    private final InsertHandler    insert;
    private final FindHandler      find;
    private final UpdateHandler    update;
    private final DeleteHandler    delete;
    private final AggregateHandler aggregate;
    private final AdminHandler     admin;
    private final GetMoreHandler   getMore;

    public CommandDispatcher(InMemoryStorage storage) {
        this.handshake = new HandshakeHandler();
        this.insert    = new InsertHandler(storage);
        this.find      = new FindHandler(storage);
        this.update    = new UpdateHandler(storage);
        this.delete    = new DeleteHandler(storage);
        this.aggregate = new AggregateHandler(storage);
        this.admin     = new AdminHandler(storage);
        this.getMore   = new GetMoreHandler();
    }

    public BsonDocument dispatch(BsonDocument command) {
        // The command name is always the first key
        String commandName = command.keySet().iterator().next().toLowerCase();
        LOG.debug("dispatch: {}", commandName);

        try {
            switch (commandName) {
                // Handshake / connection
                case "hello":
                case "ismaster":
                case "isMaster":
                    return handshake.handle(command);
                case "buildinfo":
                case "buildInfo":
                    return HandshakeHandler.buildInfo();
                case "ping":
                    return HandshakeHandler.ping();
                case "getparameter":
                case "getParameter":
                    return HandshakeHandler.getParameter(command);
                case "endsessions":
                case "endSessions":
                case "logout":
                    return ok();

                // CRUD
                case "insert":
                    return insert.handle(command);
                case "find":
                    return find.handle(command);
                case "update":
                    return update.handle(command);
                case "delete":
                    return delete.handle(command);
                case "aggregate":
                    return aggregate.handle(command);
                case "getmore":
                case "getMore":
                    return getMore.handle(command);

                // Admin
                case "listdatabases":
                case "listDatabases":
                    return admin.listDatabases(command);
                case "listcollections":
                case "listCollections":
                    return admin.listCollections(command);
                case "drop":
                    return admin.drop(command);
                case "dropdatabase":
                case "dropDatabase":
                    return admin.dropDatabase(command);
                case "create":
                case "createCollection":
                    return admin.createCollection(command);
                case "createindexes":
                case "createIndexes":
                    return admin.createIndexes(command);
                case "count":
                    return admin.count(command, "count");
                case "countdocuments":
                case "countDocuments":
                    return admin.count(command, commandName);
                case "killcursors":
                case "killCursors":
                    return admin.killCursors(command);

                // Ignore / pass-through
                case "saslstart":
                case "saslcontinue":
                case "authenticate":
                    return ok(); // no-op auth

                default:
                    LOG.warn("Unknown command: {}", commandName);
                    return unknownCommand(commandName);
            }
        } catch (Exception ex) {
            LOG.error("Error handling command {}: {}", commandName, ex.getMessage(), ex);
            return commandError(ex.getMessage());
        }
    }

    private static BsonDocument ok() {
        return new BsonDocument("ok", new BsonDouble(1.0));
    }

    private static BsonDocument unknownCommand(String name) {
        BsonDocument r = new BsonDocument();
        r.put("ok", new BsonDouble(0.0));
        r.put("errmsg", new BsonString("no such command: '" + name + "'"));
        r.put("code", new BsonInt32(59));
        r.put("codeName", new BsonString("CommandNotFound"));
        return r;
    }

    private static BsonDocument commandError(String msg) {
        BsonDocument r = new BsonDocument();
        r.put("ok", new BsonDouble(0.0));
        r.put("errmsg", new BsonString(msg != null ? msg : "internal error"));
        r.put("code", new BsonInt32(1));
        r.put("codeName", new BsonString("InternalError"));
        return r;
    }
}

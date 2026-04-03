package com.smolcumentdb.protocol;

/**
 * MongoDB Wire Protocol opcodes.
 */
public final class Opcodes {
    public static final int OP_QUERY  = 2004;
    public static final int OP_REPLY  = 1;
    public static final int OP_MSG    = 2013;

    private Opcodes() {}
}

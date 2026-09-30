package splusjava;

import java.util.Arrays;
import java.util.List;

import splusjava.tl.TLCodec;
import splusjava.tl.TLConstructor;
import splusjava.tl.TLObject;
import splusjava.tl.TLSchema;
import splusjava.util.Bytes;

final class TLTests {
    private TLTests() {
    }

    static TLObject o(String name, Object... kv) {
        return TLObject.of(name, kv);
    }

    static byte[] range(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i & 0xff);
        }
        return b;
    }

    static byte[] seq(int from, int count) {
        byte[] b = new byte[count];
        for (int i = 0; i < count; i++) {
            b[i] = (byte) (from + i);
        }
        return b;
    }

    static TLObject build(String name) {
        if (name.startsWith("bytes_")) {
            int n = Integer.parseInt(name.substring(6));
            return o("auth.importAuthorization", "id", -1L, "bytes", range(n));
        }
        if (name.equals("send_min")) {
            return o("messages.sendMessage", "peer", o("inputPeerUser", "user_id", 5L, "access_hash", 77L),
                    "message", "hello", "random_id", 123456789L, "silent", Boolean.TRUE, "no_webpage", Boolean.TRUE);
        }
        if (name.equals("send_full")) {
            return o("messages.sendMessage", "peer", o("inputPeerChannel", "channel_id", 100L, "access_hash", 200L),
                    "message", "Hi there", "random_id", -5L,
                    "reply_to", o("inputReplyToMessage", "reply_to_msg_id", 10, "top_msg_id", 3),
                    "entities", Arrays.<Object>asList(o("messageEntityBold", "offset", 0, "length", 2),
                            o("messageEntityBold", "offset", 3, "length", 5)),
                    "schedule_date", 1800000000, "clear_draft", Boolean.TRUE, "noforwards", Boolean.TRUE,
                    "send_as", o("inputPeerSelf"));
        }
        if (name.equals("init")) {
            TLObject init = o("initConnection", "api_id", 1030400, "device_model", "PC 64bit", "system_version", "10",
                    "app_version", "3.9.2 A", "lang_code", "fa", "system_lang_code", "fa", "lang_pack", "",
                    "query", o("help.getConfig"));
            return o("invokeWithLayer", "layer", 182, "query", init);
        }
        if (name.equals("sendcode")) {
            return o("auth.sendCode", "phone_number", "+989121234567", "api_id", 1030400,
                    "api_hash", "6edb16cf88714a4e9a805e928c39c937", "settings", o("codeSettings"));
        }
        if (name.equals("getusers")) {
            return o("users.getUsers", "id", Arrays.<Object>asList(o("inputUserSelf"),
                    o("inputUser", "user_id", 5L, "access_hash", 6L)));
        }
        if (name.equals("history")) {
            return o("messages.getHistory", "peer", o("inputPeerChat", "chat_id", 9L), "offset_id", 0, "offset_date", 0,
                    "add_offset", 0, "limit", 20, "max_id", 0, "min_id", 0, "hash", 0L);
        }
        if (name.equals("long_msg")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                sb.append('x');
            }
            return o("messages.sendMessage", "peer", o("inputPeerSelf"), "message", sb.toString(), "random_id", 1L);
        }
        if (name.equals("utf8_msg")) {
            return o("messages.sendMessage", "peer", o("inputPeerSelf"),
                    "message", "\u0633\u0644\u0627\u0645 \u062f\u0646\u06cc\u0627 \ud83d\ude00", "random_id", 2L);
        }
        if (name.equals("msgs_ack")) {
            return o("msgs_ack", "msg_ids", new long[] {1L, 2L, -3L});
        }
        if (name.equals("req_pq_multi")) {
            return o("req_pq_multi", "nonce", seq(1, 16));
        }
        if (name.equals("pq_inner")) {
            return o("p_q_inner_data", "pq", Bytes.fromHex("17ed48941a08f981"), "p", Bytes.fromHex("494c553b"),
                    "q", Bytes.fromHex("53911073"), "nonce", seq(1, 16), "server_nonce", seq(17, 16),
                    "new_nonce", seq(33, 32));
        }
        if (name.equals("ping")) {
            return o("ping", "ping_id", -123456789012345L);
        }
        return null;
    }

    static void run() {
        T.section("TL schema");
        TLSchema s = TLSchema.get();
        T.eq(Integer.valueOf(1681), Integer.valueOf(s.size()), "schema constructor count");
        TLConstructor send = s.getByName("messages.sendMessage");
        T.ok(send != null && send.id == 0x280d096f && send.function, "messages.sendMessage id");
        T.ok(s.getById(0x74ae4240) != null && "updates".equals(s.getById(0x74ae4240).name), "updates by id");
        T.eq("X", s.getByName("invokeWithLayer").resultTypeName, "generic result type");

        T.section("TL serialization vs. reference python implementation");
        for (int i = 0; i < TLVectors.CASES.length; i++) {
            String name = TLVectors.CASES[i][0];
            String expected = TLVectors.CASES[i][1];
            TLObject obj = build(name);
            if (obj == null) {
                continue; // deserialization-only vector
            }
            T.eq(expected, Bytes.toHex(obj.toBytes()), "serialize " + name);
        }

        T.section("TL deserialization vs. reference python implementation");
        for (int i = 0; i < TLVectors.CASES.length; i++) {
            String name = TLVectors.CASES[i][0];
            byte[] raw = Bytes.fromHex(TLVectors.CASES[i][1]);
            Object parsed = TLCodec.deserialize(raw);
            // Everything must survive a parse -> serialize round trip byte for byte.
            if (parsed instanceof TLObject) {
                T.eq(TLVectors.CASES[i][1], Bytes.toHex(((TLObject) parsed).toBytes()), "round trip " + name);
            }
        }
        TLObject sm = (TLObject) TLCodec.deserialize(hex("d_short_message"));
        T.eq("updateShortMessage", sm.getName(), "short message name");
        T.eq("hi there", sm.getString("message"), "short message text");
        T.eq(Integer.valueOf(7), Integer.valueOf(sm.getInt("id")), "short message id");
        T.eq(Long.valueOf(9L), Long.valueOf(sm.getLong("user_id")), "short message user");
        T.ok(sm.getBool("out"), "short message out flag");
        T.ok(!sm.getBool("mentioned"), "short message mentioned flag absent");
        T.eq(Integer.valueOf(1700000000), Integer.valueOf(sm.getInt("date")), "short message date");
        T.eq(Integer.valueOf(1), Integer.valueOf(sm.getList("entities").size()), "short message entities");

        TLObject ups = (TLObject) TLCodec.deserialize(hex("d_updates"));
        T.eq("updates", ups.getName(), "updates name");
        List<Object> updates = ups.getList("updates");
        TLObject nm = (TLObject) updates.get(0);
        T.eq("updateNewMessage", nm.getName(), "update name");
        TLObject m = nm.getObject("message");
        T.eq("hello", m.getString("message"), "nested message text");
        T.eq(Long.valueOf(5L), Long.valueOf(m.getObject("peer_id").getLong("user_id")), "peer id");
        T.eq(Long.valueOf(9L), Long.valueOf(m.getObject("from_id").getLong("user_id")), "from id");
        TLObject user = (TLObject) ups.getList("users").get(0);
        T.eq("Ali", user.getString("first_name"), "user first name");
        T.eq(Long.valueOf(555L), Long.valueOf(user.getLong("access_hash")), "user access hash");
        T.ok(user.getBool("self"), "user self flag");
        T.eq("userTypeNormal", user.getObject("user_type").getName(), "user type");
        TLObject chat = (TLObject) ups.getList("chats").get(0);
        T.eq("Grp", chat.getString("title"), "chat title");
        T.ok(chat.getBool("creator"), "chat creator flag");

        TLObject err = (TLObject) TLCodec.deserialize(hex("d_rpc_error"));
        T.eq("rpc_error", err.getName(), "rpc_error name");
        T.eq("FLOOD_WAIT_7", err.getString("error_message"), "rpc_error message (bytes->string)");
        TLObject info = (TLObject) TLCodec.deserialize(hex("d_bool_vector_ints"));
        T.eq(Integer.valueOf(3), Integer.valueOf(info.getList("msg_ids").size()), "vector<long> length");
        T.eq(Long.valueOf(6L), info.getList("msg_ids").get(1), "vector<long> element");

        T.section("TL misc");
        Object b = TLCodec.deserialize(Bytes.fromHex("b5757299"));
        T.eq(Boolean.TRUE, b, "boolTrue");
        T.eq(Boolean.FALSE, TLCodec.deserialize(Bytes.fromHex("379779bc")), "boolFalse");
        try {
            TLCodec.deserialize(Bytes.fromHex("deadbeef00000000"));
            T.ok(false, "unknown constructor must fail");
        } catch (TLException e) {
            T.eq(Integer.valueOf(0xefbeadde), Integer.valueOf(e.getConstructorId()), "unknown constructor id");
        }
        try {
            TLObject.create("messages.sendMessage").set("nope", 1);
            T.ok(false, "unknown field must fail");
        } catch (IllegalArgumentException e) {
            T.ok(e.getMessage().contains("peer"), "unknown field message lists fields");
        }
        try {
            TLObject.create("messages.sendMessage").set("message", "x").toBytes();
            T.ok(false, "missing required field must fail");
        } catch (IllegalStateException e) {
            T.ok(e.getMessage().contains("peer"), "missing field message");
        }
        // gzip round trip inside a gzip_packed wrapper
        byte[] inner = TLObject.of("ping", "ping_id", 42L).toBytes();
        byte[] gz = TLCodec.gzip(inner);
        splusjava.tl.TLWriter w = new splusjava.tl.TLWriter();
        w.writeInt(TLCodec.GZIP_PACKED_ID);
        w.writeTLBytes(gz);
        TLObject unpacked = (TLObject) TLCodec.deserialize(w.toByteArray());
        T.eq(Long.valueOf(42L), Long.valueOf(unpacked.getLong("ping_id")), "gzip_packed unwrap");
        // vector<long> function result read with a type hint (raw longs)
        splusjava.tl.TLWriter vw = new splusjava.tl.TLWriter();
        vw.writeInt(TLCodec.VECTOR_ID);
        vw.writeInt(2);
        vw.writeLong(11L);
        vw.writeLong(-12L);
        Object res = TLCodec.readResult(vw.toByteArray(), TLObject.create("auth.dropTempAuthKeys").effectiveResultType() == null
                ? null : splusjava.tl.TLType.parse("Vector<long>"));
        T.eq(Long.valueOf(-12L), ((List<?>) res).get(1), "Vector<long> with hint");
        T.ok(TLObject.of("users.getUsers", "id", new long[0]).toJson().startsWith("{\"_\":\"users.getUsers\""), "json output");
    }

    private static byte[] hex(String name) {
        for (int i = 0; i < TLVectors.CASES.length; i++) {
            if (TLVectors.CASES[i][0].equals(name)) {
                return Bytes.fromHex(TLVectors.CASES[i][1]);
            }
        }
        throw new IllegalArgumentException(name);
    }
}

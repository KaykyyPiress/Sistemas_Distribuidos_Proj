package br.fei.cc7261;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import java.util.*;

public class MsgHelper {

    public static byte[] pack(Map<String, Object> msg) throws Exception {
        MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
        packMap(packer, msg);
        return packer.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void packMap(MessageBufferPacker p, Map<String, Object> map) throws Exception {
        p.packMapHeader(map.size());
        for (Map.Entry<String, Object> e : map.entrySet()) {
            p.packString(e.getKey());
            Object v = e.getValue();
            if (v instanceof String) p.packString((String) v);
            else if (v instanceof Double) p.packDouble((Double) v);
            else if (v instanceof Float) p.packFloat((Float) v);
            else if (v instanceof Long) p.packLong((Long) v);
            else if (v instanceof Integer) p.packInt((Integer) v);
            else if (v instanceof Map) packMap(p, (Map<String, Object>) v);
            else if (v instanceof List) packList(p, (List<Object>) v);
            else if (v == null) p.packNil();
            else p.packString(v.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void packList(MessageBufferPacker p, List<Object> list) throws Exception {
        p.packArrayHeader(list.size());
        for (Object v : list) {
            if (v instanceof String) p.packString((String) v);
            else if (v instanceof Double) p.packDouble((Double) v);
            else if (v instanceof Map) packMap(p, (Map<String, Object>) v);
            else if (v == null) p.packNil();
            else p.packString(v.toString());
        }
    }

    public static Map<String, Object> unpack(byte[] data) throws Exception {
        MessageUnpacker u = MessagePack.newDefaultUnpacker(data);
        return unpackMap(u);
    }

    private static Map<String, Object> unpackMap(MessageUnpacker u) throws Exception {
        int size = u.unpackMapHeader();
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String key = u.unpackString();
            map.put(key, unpackValue(u));
        }
        return map;
    }

    private static Object unpackValue(MessageUnpacker u) throws Exception {
        var fmt = u.getNextFormat().getValueType();
        switch (fmt) {
            case STRING: return u.unpackString();
            case INTEGER: return u.unpackLong();
            case FLOAT: return u.unpackDouble();
            case MAP: return unpackMap(u);
            case ARRAY: {
                int n = u.unpackArrayHeader();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < n; i++) list.add(unpackValue(u));
                return list;
            }
            case NIL: u.unpackNil(); return null;
            case BOOLEAN: return u.unpackBoolean();
            default: u.skipValue(); return null;
        }
    }
}

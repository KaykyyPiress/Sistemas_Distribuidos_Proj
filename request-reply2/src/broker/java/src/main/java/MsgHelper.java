import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MsgHelper {

    public static byte[] pack(Map<String, Object> msg) throws Exception {
        MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
        packMap(packer, msg);
        packer.close();
        return packer.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void packMap(MessageBufferPacker packer, Map<String, Object> map) throws Exception {
        packer.packMapHeader(map.size());

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            packer.packString(entry.getKey());
            packValue(packer, entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static void packList(MessageBufferPacker packer, List<Object> list) throws Exception {
        packer.packArrayHeader(list.size());

        for (Object value : list) {
            packValue(packer, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void packValue(MessageBufferPacker packer, Object value) throws Exception {
        if (value == null) {
            packer.packNil();
        } else if (value instanceof String) {
            packer.packString((String) value);
        } else if (value instanceof Integer) {
            packer.packInt((Integer) value);
        } else if (value instanceof Long) {
            packer.packLong((Long) value);
        } else if (value instanceof Float) {
            packer.packFloat((Float) value);
        } else if (value instanceof Double) {
            packer.packDouble((Double) value);
        } else if (value instanceof Boolean) {
            packer.packBoolean((Boolean) value);
        } else if (value instanceof Map<?, ?>) {
            packMap(packer, (Map<String, Object>) value);
        } else if (value instanceof List<?>) {
            packList(packer, (List<Object>) value);
        } else {
            packer.packString(value.toString());
        }
    }

    public static Map<String, Object> unpack(byte[] data) throws Exception {
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data);
        Map<String, Object> map = unpackMap(unpacker);
        unpacker.close();
        return map;
    }

    private static Map<String, Object> unpackMap(MessageUnpacker unpacker) throws Exception {
        int size = unpacker.unpackMapHeader();
        Map<String, Object> map = new LinkedHashMap<>();

        for (int i = 0; i < size; i++) {
            String key = unpacker.unpackString();
            Object value = unpackValue(unpacker);
            map.put(key, value);
        }

        return map;
    }

    private static List<Object> unpackList(MessageUnpacker unpacker) throws Exception {
        int size = unpacker.unpackArrayHeader();
        List<Object> list = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            list.add(unpackValue(unpacker));
        }

        return list;
    }

    private static Object unpackValue(MessageUnpacker unpacker) throws Exception {
        switch (unpacker.getNextFormat().getValueType()) {
            case NIL:
                unpacker.unpackNil();
                return null;

            case BOOLEAN:
                return unpacker.unpackBoolean();

            case INTEGER:
                return unpacker.unpackLong();

            case FLOAT:
                return unpacker.unpackDouble();

            case STRING:
                return unpacker.unpackString();

            case ARRAY:
                return unpackList(unpacker);

            case MAP:
                return unpackMap(unpacker);

            default:
                unpacker.skipValue();
                return null;
        }
    }
}
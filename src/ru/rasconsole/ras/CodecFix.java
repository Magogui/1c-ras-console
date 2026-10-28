package ru.rasconsole.ras;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

import org.jboss.netty.buffer.ChannelBuffer;

import com._1c.v8.swp.codec.IServiceWireCodec;
import com._1c.v8.swp.codec.IServiceWireCodecFactory;
import com._1c.v8.swp.codec.ServiceWireCodecException;
import com._1c.v8.swp.codec.ServiceWireType;
import com._1c.v8.swp.endpoints.EndpointId;

/**
 * Обход ошибки библиотеки 1С (swp 1.0.3, кодек {@code BinaryCodecV1_0}): чтение строк и запись чисел и UUID
 * идут через один общий статический буфер {@code temp}. Когда несколько соединений с RAS работают
 * одновременно (опрос всех серверов), потоки затирают буфер друг другу: строки склеиваются из двух
 * (имя кластера «Local cluster» поверх «Локальный кластер» → «…cluster�й кластер»), а в запросах могут
 * испортиться числа и идентификаторы.
 * <p>
 * Библиотеки подписаны, подменить класс нельзя, поэтому после создания коннектора фабрика кодеков внутри
 * него заменяется обёрткой: каждый вызов кодека выполняется под одной общей блокировкой. Вызовы
 * короткие (одно значение), на скорость это не влияет.
 */
public final class CodecFix {

    /** Общая для всех соединений: буфер в библиотеке тоже общий (static). */
    private static final Object LOCK = new Object();
    private static final int MAX_OBJECTS = 5000;

    private CodecFix() {
    }

    /**
     * Найти в графе объектов коннектора фабрики кодеков и кодеки 1С и заменить их безопасными обёртками.
     * @return сколько полей заменено (0 — уже заменено ранее или не найдено)
     */
    public static int patch(Object root) {
        int patched = 0;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && seen.size() < MAX_OBJECTS) {
            Object o = queue.poll();
            if (o == null || !seen.add(o)) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (!interesting(c.getName())) continue;
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive() || Modifier.isStatic(f.getModifiers())) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (RuntimeException | IllegalAccessException e) {
                        continue;
                    }
                    if (v == null) continue;
                    try {
                        if (v instanceof IServiceWireCodecFactory fac && !(v instanceof SafeFactory)
                            && f.getType().isAssignableFrom(SafeFactory.class)) {
                            f.set(o, new SafeFactory(fac));
                            patched++;
                            continue;
                        }
                        if (v instanceof IServiceWireCodec codec && !(v instanceof SafeCodec)
                            && f.getType().isAssignableFrom(SafeCodec.class)) {
                            f.set(o, new SafeCodec(codec));
                            patched++;
                            continue;
                        }
                    } catch (IllegalAccessException | RuntimeException e) {
                        System.err.println("CodecFix: " + c.getName() + "." + f.getName() + ": " + e);
                    }
                    if (interesting(v.getClass().getName())) queue.add(v);
                }
            }
        }
        return patched;
    }

    /** Классы, внутри которых ищем: библиотеки 1С и netty (фабрика кодеков лежит в фабрике каналов netty). */
    private static boolean interesting(String className) {
        return className.startsWith("com._1c.") || className.startsWith("org.jboss.netty.bootstrap.")
            || className.startsWith("org.jboss.netty.channel.") && !className.contains("socket");
    }

    /** Фабрика, выдающая кодеки 1С в безопасной обёртке. */
    static final class SafeFactory implements IServiceWireCodecFactory {
        private final IServiceWireCodecFactory d;

        SafeFactory(IServiceWireCodecFactory d) {
            this.d = d;
        }

        @Override
        public short getDefaultVersion() {
            return d.getDefaultVersion();
        }

        @Override
        public IServiceWireCodec createCodec() {
            return new SafeCodec(d.createCodec());
        }

        @Override
        public IServiceWireCodec createCodec(short version) throws ServiceWireCodecException {
            return new SafeCodec(d.createCodec(version));
        }
    }

    /** Кодек 1С, все вызовы которого идут под общей блокировкой. */
    static final class SafeCodec implements IServiceWireCodec {
        private final IServiceWireCodec d;

        SafeCodec(IServiceWireCodec d) {
            this.d = d;
        }

        @Override
        public short getVersion() {
            return d.getVersion();
        }

        // ---- запись ----

        @Override
        public void encodeBoolean(ChannelBuffer b, boolean v) {
            synchronized (LOCK) {
                d.encodeBoolean(b, v);
            }
        }

        @Override
        public void encodeByte(ChannelBuffer b, int v) {
            synchronized (LOCK) {
                d.encodeByte(b, v);
            }
        }

        @Override
        public void encodeChar(ChannelBuffer b, int v) {
            synchronized (LOCK) {
                d.encodeChar(b, v);
            }
        }

        @Override
        public void encodeShort(ChannelBuffer b, int v) {
            synchronized (LOCK) {
                d.encodeShort(b, v);
            }
        }

        @Override
        public void encodeInt(ChannelBuffer b, int v) {
            synchronized (LOCK) {
                d.encodeInt(b, v);
            }
        }

        @Override
        public void encodeLong(ChannelBuffer b, long v) {
            synchronized (LOCK) {
                d.encodeLong(b, v);
            }
        }

        @Override
        public void encodeFloat(ChannelBuffer b, float v) {
            synchronized (LOCK) {
                d.encodeFloat(b, v);
            }
        }

        @Override
        public void encodeDouble(ChannelBuffer b, double v) {
            synchronized (LOCK) {
                d.encodeDouble(b, v);
            }
        }

        @Override
        public void encodeSize(ChannelBuffer b, int v) {
            synchronized (LOCK) {
                d.encodeSize(b, v);
            }
        }

        @Override
        public void encodeNullableSize(ChannelBuffer b, Integer v) {
            synchronized (LOCK) {
                d.encodeNullableSize(b, v);
            }
        }

        @Override
        public void encodeString(ChannelBuffer b, String v) {
            synchronized (LOCK) {
                d.encodeString(b, v);
            }
        }

        @Override
        public void encodeUuid(ChannelBuffer b, UUID v) {
            synchronized (LOCK) {
                d.encodeUuid(b, v);
            }
        }

        @Override
        public void encodeType(ChannelBuffer b, ServiceWireType v) {
            synchronized (LOCK) {
                d.encodeType(b, v);
            }
        }

        @Override
        public void encodeEndpointId(ChannelBuffer b, EndpointId v) {
            synchronized (LOCK) {
                d.encodeEndpointId(b, v);
            }
        }

        @Override
        public void encodeByteArray(ChannelBuffer b, byte[] v) {
            synchronized (LOCK) {
                d.encodeByteArray(b, v);
            }
        }

        // ---- чтение ----

        @Override
        public boolean decodeBoolean(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeBoolean(b);
            }
        }

        @Override
        public byte decodeByte(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeByte(b);
            }
        }

        @Override
        public short decodeUnsignedByte(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeUnsignedByte(b);
            }
        }

        @Override
        public char decodeChar(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeChar(b);
            }
        }

        @Override
        public short decodeShort(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeShort(b);
            }
        }

        @Override
        public int decodeUnsignedShort(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeUnsignedShort(b);
            }
        }

        @Override
        public int decodeInt(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeInt(b);
            }
        }

        @Override
        public long decodeUnsignedInt(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeUnsignedInt(b);
            }
        }

        @Override
        public long decodeLong(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeLong(b);
            }
        }

        @Override
        public float decodeFloat(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeFloat(b);
            }
        }

        @Override
        public double decodeDouble(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeDouble(b);
            }
        }

        @Override
        public int decodeSize(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeSize(b);
            }
        }

        @Override
        public Integer decodeNullableSize(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeNullableSize(b);
            }
        }

        @Override
        public String decodeString(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeString(b);
            }
        }

        @Override
        public UUID decodeUuid(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeUuid(b);
            }
        }

        @Override
        public ServiceWireType decodeType(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeType(b);
            }
        }

        @Override
        public EndpointId decodeEndpointId(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeEndpointId(b);
            }
        }

        @Override
        public byte[] decodeByteArray(ChannelBuffer b) throws ServiceWireCodecException {
            synchronized (LOCK) {
                return d.decodeByteArray(b);
            }
        }
    }
}

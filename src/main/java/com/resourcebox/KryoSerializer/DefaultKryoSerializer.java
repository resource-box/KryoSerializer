package com.resourcebox.KryoSerializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import java.util.Arrays;

/**
 * Apache Pool2 기반의 Kryo Serializer 클래스입니다.
 */
public class DefaultKryoSerializer {

    // config
    private static final int BUFFER_SIZE = 1024;

    // thread local
    private static final ThreadLocal<Kryo> kryos = ThreadLocal.withInitial(() -> {
        Kryo k = new Kryo();
        k.setReferences(false);
        return k;
    });

    // pool
    private static final ObjectPool<Input> inputs = new GenericObjectPool<>(new BasePooledObjectFactory<Input>() {
        public Input create() { return new Input(BUFFER_SIZE); }

        public PooledObject<Input> wrap(Input obj) { return new DefaultPooledObject<>(obj); }
    });

    private static final ObjectPool<Output> outputs = new GenericObjectPool<>(new BasePooledObjectFactory<Output>() {
        public Output create() { return new Output(BUFFER_SIZE); }

        public PooledObject<Output> wrap(Output obj) { return new DefaultPooledObject<>(obj); }
    });

    /**
     * 바이트 배열을 원본 데이터 타입으로 역직렬화합니다.
     * @param body 역직렬화 대상 바이트 배열
     * @return 역직렬화된 원본 객체
     * @param <T> 원본 데이터 타입
     */
    public static <T> T deserialize(byte[] body) {
        Input input = null;
        T result = null;

        try {
            input = inputs.borrowObject();
            Kryo k = kryos.get();

            // De-Serialize
            byte[] copyBody = Arrays.copyOf(body, body.length);
            input.setBuffer(copyBody);
            result = (T) k.readClassAndObject(input);

            // Return
            inputs.returnObject(input);
        } catch (Exception e) {
            throw new RuntimeException("DESERIALIZATION FAILED", e);
        }

        return result;
    }

    /**
     * 객체를 바이트 배열로 직렬화합니다.
     * @param body 직렬화 대상 원본 객체
     * @return 직렬화된 바이트 배열
     */
    public static byte[] serialize(Object body) {
        Output output = null;
        byte[] result = null;

        try {
            output = outputs.borrowObject();
            output.clear();
            Kryo k = kryos.get();

            // Serialize
            k.writeClassAndObject(output, body);

            // Return
            result = output.toBytes();
        } catch (Exception e) {
            throw new RuntimeException("SERIALIZATION FAILED", e);
        }

        return result;
    }

    /**
     * 기존 객체를 기반으로 신규 객체를 생성합니다.
     * @param body Deep Copy 대상 원본 객체
     * @return Deep Copy 객체
     * @param <E> 원본 데이터 타입
     */
    public static <E> E deepCopy(E body) {
        byte[] bytes = null;
        E result = null;

        try {
            bytes = serialize(body);
            result = deserialize(bytes);
        } catch (Exception ex) {
            throw new RuntimeException("DEEP COPY TASK FAILED", ex);
        }

        return result;
    }

}

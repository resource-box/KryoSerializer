package com.resourcebox.KryoSerializer;

import com.esotericsoftware.kryo.Kryo;
import com.resourcebox.KryoSerializer.tool.KryoHolder;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import java.util.Objects;

/**
 * Kryo 객체를 Pool로 관리하는 Pool 기반 Serializer 클래스입니다.
 * Kryo 객체는 Thread-Safe 하지 않으므로, Pool을 사용하여 여러 스레드에서 안전하게 공유할 수 있도록 합니다.
 *
 * @Warning Thread 수가 가변적인 환경에 적합합니다.
 */
public class PoolBasedSerializer {

    // pool
    private static final GenericObjectPool<KryoHolder> pool;

    static {
        // config
        int maxTotal = 256;                 // 코어 수의 4~8배 수준으로 제한
        int initBuf = 8_192;                // 8KB (객체 무한 생성 시 메모리 충격 완화)
        int maxBuf = 1_024 * 1_024 * 10;    // 10MB (Kryo가 자동으로 버퍼를 늘려줌)

        Objects.checkIndex(0, 1); // no-op (JDK9+) - 원하면 제거 가능
        GenericObjectPoolConfig<KryoHolder> cfg = new GenericObjectPoolConfig<>();
        cfg.setMaxTotal(maxTotal);
        cfg.setMaxIdle(maxTotal);
        cfg.setMinIdle(maxTotal);
        cfg.setBlockWhenExhausted(true);

        pool = new GenericObjectPool<>(new BasePooledObjectFactory<>() {
            @Override
            public KryoHolder create() {
                Kryo kryo = new Kryo();

                // 풀링(Pooling) 환경에서는 Kryo 객체를 생성하는 스레드와, 그 객체를 빌려다 쓰는 스레드가 다를 확률이 매우 높습니다.
                // 만약 create() 시점에 Thread.currentThread().getContextClassLoader()를 설정해 버리면,
                // 해당 Kryo 객체를 처음 생성한 스레드의 클래스로더가 영구적으로 박제됩니다.

                // 스프링 부트(Spring Boot), 톰캣(Tomcat), 스파크(Spark) 같은 복잡한 프레임워크나 WAS 환경에서는
                // 스레드마다, 혹은 애플리케이션 모듈마다 사용하는 스레드 컨텍스트 클래스로더(TCCL)가 동적으로 변할 수 있습니다.

                // 풀에 있는 Kryo가 옛날 클래스로더를 계속 쥐고 있으면
                // Kryo 객체가 강한 참조(Strong Reference) 형태로 해당 클래스로더를 계속 참조하게 되어
                // 메모리 누수(Memory Leak) 현상이 발생할 수 있습니다.

                // Kryo 객체를 사용할 때마다 현재 실행 중인 스레드의 클래스로더로 덮어씌우는 방식으로 설정하면,
                // Kryo가 현재 문맥(Context)에 맞는 클래스들을 정확하게 찾아내어 객체로 복원할 수 있습니다.

                // kryo.setClassLoader(Thread.currentThread().getContextClassLoader()); // (사용하지 않음)

                return new KryoHolder(kryo, initBuf, maxBuf);
            }

            /**
             * 풀에서 객체를 관리하기 위해 PooledObject 으로 감싸는 메서드입니다.
             * @param obj KryoHolder 객체
             * @return PooledObject 객체
             */
            @Override
            public PooledObject<KryoHolder> wrap(KryoHolder obj) {
                return new DefaultPooledObject<>(obj);
            }

            /**
             * 풀에서 객체를 빌릴 때마다 호출되는 메서드입니다.
             * @param p PooledObject 객체
             */
            @Override
            public void activateObject(PooledObject<KryoHolder> p) {
                p.getObject().resetForBorrow();

                // activate 시점에 현재 스레드의 TCCL로 보정
                p.getObject().kryo.setClassLoader(Thread.currentThread().getContextClassLoader());
            }

            /**
             * 풀에 객체를 반환할 때마다 호출되는 메서드입니다.
             * @param p PooledObject 객체
             */
            @Override
            public void passivateObject(PooledObject<KryoHolder> p) {
                p.getObject().resetForReturn();
            }

            /**
             * 풀에서 객체를 폐기할 때 호출되는 메서드입니다.
             * @param p PooledObject 객체
             */
            @Override
            public void destroyObject(PooledObject<KryoHolder> p) {
                // 참조를 끊거나 로깅을 넣고 싶으면 여기서
            }
        }, cfg);
    }

    /**
     * 객체를 바이트 배열로 직렬화합니다.
     * @param object 직렬화 대상 원본 객체
     * @return 직렬화된 바이트 배열
     * @param <T> 원본 데이터 타입
     * @throws Exception 예외
     */
    public static <T> byte[] serialize(T object) throws Exception {
        KryoHolder holder = borrow();
        boolean ok = false;

        try {
            holder.output.setPosition(0); // 안전을 위한 권장 사항
            holder.kryo.writeClassAndObject(holder.output, object);
            ok = true;

            return holder.output.toBytes();
        } catch (Exception e) {
            throw new RuntimeException("SERIALIZATION FAILED", e);
        } finally {
            if (ok) release(holder);
            else invalidate(holder);
        }
    }

    /**
     * 바이트 배열을 원본 데이터 타입으로 역직렬화합니다.
     * @param bytes 역직렬화 대상 바이트 배열
     * @return 역직렬화된 원본 객체
     * @param <T> 원본 데이터 타입
     * @throws Exception 예외
     */
    public static <T> T deserialize(byte[] bytes) throws Exception {
        KryoHolder holder = borrow();
        boolean ok = false;

        try {
            holder.input.setBuffer(bytes);
            ok = true;

            return (T) holder.kryo.readClassAndObject(holder.input);
        } catch (Exception e) {
            throw new RuntimeException("DESERIALIZATION FAILED", e);
        } finally {
            if (ok) release(holder);
            else invalidate(holder);
        }
    }

    /**
     * KryoHolder 객체를 빌려줍니다.
     * borrowObject() 호출 시 Output/Input 객체를 초기화합니다.
     * @return KryoHolder 객체
     * @throws Exception 예외
     */
    public static KryoHolder borrow() throws Exception {
        return pool.borrowObject();
    }

    /**
     * KryoHolder 객체를 Pool에 반환합니다.
     * borrowObject() 호출 시 Output/Input 객체를 초기화합니다.
     * @param h KryoHolder 객체
     */
    public static void release(KryoHolder h) {
        if (h == null) return;
        pool.returnObject(h);
    }

    /**
     * KryoHolder 객체를 폐기합니다.
     * 예외가 발생하거나 오염된 객체는 invalidateObject()로 폐기하는 것을 권장합니다.
     * @param h KryoHolder 객체
     */
    public static void invalidate(KryoHolder h) {
        if (h == null) return;
        try {
            pool.invalidateObject(h);
        } catch (Exception ignored) {
            // invalidate 실패해도 어차피 해당 holder는 버림 취급
        }
    }

    // (선택) 운영 중 상태 확인용
    public int getNumActive() { return pool.getNumActive(); }
    public int getNumIdle() { return pool.getNumIdle(); }

}
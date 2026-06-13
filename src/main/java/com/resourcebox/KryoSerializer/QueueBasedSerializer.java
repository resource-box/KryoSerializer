package com.resourcebox.KryoSerializer;

import com.esotericsoftware.kryo.Kryo;
import com.resourcebox.KryoSerializer.tool.KryoHolder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kryo 객체를 ConcurrentLinkedQueue 기반으로 관리하는 Serializer 클래스입니다. (Lock Free)
 * Kryo 객체는 Thread-Safe 하지 않으므로, 동시성 자료구조 큐를 사용하여 여러 스레드에서 안전하게 공유할 수 있도록 합니다.
 * 멀티스레드 환경에서 경쟁 병목을 방지합니다.
 */
public class QueueBasedSerializer {

    // config
    private static final int MAX_CAPACITY = 256;       // 코어 수의 4~8배 수준으로 제한
    private static final int INIT_BUF = 8_192;         // 8KB (객체 무한 생성 시 메모리 충격 완화)
    private static final int MAX_BUF = 10_485_760;     // 10MB (Kryo가 자동으로 버퍼를 늘려줌)

    // queue
    private static final ConcurrentLinkedQueue<KryoHolder> pool = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger poolSize = new AtomicInteger(0);

    /**
     * 풀에서 KryoHolder 객체를 빌려옵니다. 풀이 비어있으면 새로 생성합니다.
     */
    private static KryoHolder obtain() {
        KryoHolder holder = pool.poll();
        if (holder != null) {
            poolSize.decrementAndGet();
        } else {
            Kryo kryo = new Kryo();
            // 직렬화 실패 방지를 위한 설정 추가
            kryo.setRegistrationRequired(false); // 클래스 등록 없이 사용 가능
            kryo.setReferences(true); // 순환 참조 지원
            holder = new KryoHolder(kryo, INIT_BUF, MAX_BUF);
        }
        return holder;
    }

    /**
     * 사용이 끝난 KryoHolder 객체를 풀에 반납합니다.
     * @param holder 반납할 KryoHolder 객체
     */
    private static void free(KryoHolder holder) {
        if (holder != null) {
            if (poolSize.get() < MAX_CAPACITY) {
                holder.resetForReturn();
                pool.offer(holder);
                poolSize.incrementAndGet();
            }
            // MAX_CAPACITY를 초과하면 큐에 넣지 않고 버림 (GC가 자동으로 수거)
        }
    }

    /**
     * 객체를 바이트 배열로 직렬화합니다.
     * @param object 직렬화 대상 원본 객체
     * @return 직렬화된 바이트 배열
     * @param <T> 원본 데이터 타입
     */
    public static <T> byte[] serialize(T object) {
        KryoHolder holder = obtain();
        boolean success = false;
        try {
            holder.resetForBorrow();
            holder.kryo.setClassLoader(Thread.currentThread().getContextClassLoader());
            holder.kryo.writeClassAndObject(holder.output, object);
            byte[] result = holder.output.toBytes();
            success = true;
            return result;
        } catch (Exception e) {
            throw new RuntimeException("SERIALIZATION FAILED", e);
        } finally {
            if (success) {
                free(holder);
            }
            // 실패 시 holder를 반납하지 않아 오염된 객체가 풀에 반납되는 것을 방지
        }
    }

    /**
     * 바이트 배열을 원본 데이터 타입으로 역직렬화합니다.
     * @param bytes 역직렬화 대상 바이트 배열
     * @return 역직렬화된 원본 객체
     * @param <T> 원본 데이터 타입
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] bytes) {
        KryoHolder holder = obtain();
        boolean success = false;
        try {
            holder.resetForBorrow();
            holder.kryo.setClassLoader(Thread.currentThread().getContextClassLoader());
            holder.input.setBuffer(bytes);
            T result = (T) holder.kryo.readClassAndObject(holder.input);
            success = true;
            return result;
        } catch (Exception e) {
            throw new RuntimeException("DESERIALIZATION FAILED", e);
        } finally {
            if (success) {
                free(holder);
            }
            // 실패 시 holder를 반납하지 않아 오염된 객체가 풀에 반납되는 것을 방지
        }
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
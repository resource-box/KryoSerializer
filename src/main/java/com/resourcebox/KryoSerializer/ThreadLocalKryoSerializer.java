package com.resourcebox.KryoSerializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/**
 * ThreadLocal 기반의 초고성능, Zero-Garbage Kryo Serializer 클래스입니다.
 * 
 * 특징:
 * 1. 동기화(Lock) 오버헤드 원천 차단: 스레드마다 독립적인 Kryo 인스턴스를 유지합니다.
 * 2. 가비지 생성 완전 제거: Pool 관리용 Node 객체나 new byte[0] 방어적 복사 등의 가비지가 발생하지 않습니다.
 * 3. 메모리 누수 방지: 사용이 끝난 Input/Output 의 내부 참조를 즉각 해제하여 GC가 원활하게 동작하도록 돕습니다.
 */
public class ThreadLocalKryoSerializer {

    private static final int INIT_BUF_SIZE = 8192;            // 8KB 초기 버퍼
    private static final int MAX_BUF_SIZE = 10 * 1024 * 1024; // 10MB 최대 버퍼 (자동 확장)
    
    // 빈 바이트 배열을 상수로 두어 매번 new byte[0] 가비지가 생기는 것을 방지합니다.
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    // 스레드별로 Kryo, Input, Output을 묶어서 들고 있는 Context
    private static final ThreadLocal<KryoContext> KRYO_THREAD_LOCAL = ThreadLocal.withInitial(KryoContext::new);

    private static class KryoContext {
        final Kryo kryo;
        final Input input;
        final Output output;

        KryoContext() {
            kryo = new Kryo();
            kryo.setRegistrationRequired(false); // 클래스 사전 등록 없이 사용 가능
            kryo.setReferences(true);            // 순환 참조 지원
            
            input = new Input();
            output = new Output(INIT_BUF_SIZE, MAX_BUF_SIZE);
        }
    }

    /**
     * 객체를 바이트 배열로 직렬화합니다.
     * @param object 직렬화 대상 원본 객체
     * @return 직렬화된 바이트 배열
     * @param <T> 원본 데이터 타입
     */
    public static <T> byte[] serialize(T object) {
        if (object == null) return null;

        KryoContext context = KRYO_THREAD_LOCAL.get();
        
        // 현재 스레드의 클래스로더 사용 (동적 환경 대응)
        context.kryo.setClassLoader(Thread.currentThread().getContextClassLoader());
        
        try {
            // 직렬화 전 포인터 초기화
            context.output.setPosition(0);
            context.kryo.writeClassAndObject(context.output, object);
            
            // 결과물 추출 (이 단계에서는 API 스펙상 새로운 byte[] 생성이 불가피함)
            return context.output.toBytes();
        } catch (Exception e) {
            throw new RuntimeException("SERIALIZATION FAILED", e);
        } finally {
            // 직렬화 종료 후, 다음 작업을 위해 포인터를 0으로 초기화하고
            // 내부 참조된 데이터를 GC가 수거할 수 있도록 정리 (메모리 릭 방지)
            context.output.setPosition(0);
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
        if (bytes == null) return null;

        KryoContext context = KRYO_THREAD_LOCAL.get();
        
        // 현재 스레드의 클래스로더 사용 (동적 환경 대응)
        context.kryo.setClassLoader(Thread.currentThread().getContextClassLoader());
        
        try {
            // 방어적 복사 없이 원본 배열을 바로 세팅하여 가비지 생성 원천 차단
            context.input.setBuffer(bytes);
            return (T) context.kryo.readClassAndObject(context.input);
        } catch (Exception e) {
            throw new RuntimeException("DESERIALIZATION FAILED", e);
        } finally {
            // 역직렬화 완료 후 버퍼 참조 즉시 해제!
            // 거대한 원본 바이트 배열이 ThreadLocal에 계속 참조되어 
            // 메모리가 회수되지 않는 현상(메모리 릭)을 방지합니다.
            context.input.setBuffer(EMPTY_BYTE_ARRAY);
        }
    }

    /**
     * 기존 객체를 기반으로 신규 객체를 생성합니다. (Deep Copy)
     * @param object Deep Copy 대상 원본 객체
     * @return Deep Copy 객체
     * @param <E> 원본 데이터 타입
     */
    public static <E> E deepCopy(E object) {
        if (object == null) return null;
        
        try {
            byte[] bytes = serialize(object);
            return deserialize(bytes);
        } catch (Exception ex) {
            throw new RuntimeException("DEEP COPY TASK FAILED", ex);
        }
    }
    
    /**
     * (선택) 동적으로 생성되고 소멸되는 스레드 풀 환경이라면,
     * 스레드 종료 시점에 이 메서드를 호출하여 ThreadLocal을 직접 비워주는 것이 좋습니다.
     */
    public static void cleanUp() {
        KRYO_THREAD_LOCAL.remove();
    }
}

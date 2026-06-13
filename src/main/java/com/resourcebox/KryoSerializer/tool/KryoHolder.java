package com.resourcebox.KryoSerializer.tool;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/**
 * Kryo 객체와 관련된 Output, Input 객체를 함께 보유하는 클래스입니다.
 * Kryo 객체는 Thread-Safe 하지 않으므로, Wrapper 클래스를 구성해 관리합니다.
 */
public final class KryoHolder {

    // Output과 Input 객체를 매번 new로 생성하면 직렬화가 일어날 때마다 새로운 byte 배열이 힙 메모리에 할당됩니다.
    // 이는 가비지 컬렉터(GC)에 큰 부담을 줍니다.
    // KryoHolder에 고정해두면 한 번 할당된 버퍼를 계속 재사용할 수 있습니다.

    public final Kryo kryo;
    public final Output output; // Serialization 시 재사용되는 Output 객체
    public final Input input; // Deserialization 시 재사용되는 Input 객체

    public KryoHolder(Kryo kryo, int initialBufferSize, int maxBufferSize) {
        this.kryo = kryo;
        this.output = new Output(initialBufferSize, maxBufferSize);
        this.input = new Input();
    }

    // Output은 내부적으로 position이라는 포인터를 가집니다.
    // setPosition(0)을 하지 않으면 이전 직렬화 때 썼던 데이터 뒤에 새로운 데이터가 붙어버려 데이터가 깨집니다.

    // Input에 큰 바이트 배열을 넣어뒀다가 초기화(setBuffer(new byte[0]))하지 않고 풀에 반납하면,
    // 해당 KryoHolder가 쓰이지 않는 동안에도 큰 바이트 배열이 메모리를 계속 점유하게 됩니다.

    /**
     * Kryo 객체를 빌릴 때마다 Output과 Input을 초기화합니다.
     * 이를 통해 이전 사용의 잔여 데이터를 제거하고, 버퍼 참조를 해제하여 메모리 누수를 방지합니다.
     */
    public void resetForBorrow() {
        // Output 포인터 초기화
        // Output 객체가 점유하고 있는 버퍼 크기를 디폴트 크기로 초기화합니다.
        // Kryo v4.0.0 Output : reset() → setPosition(0)
        output.setPosition(0);

        // (선택) Output 객체가 내부에서 사용하던 참조 해제
        // output.clear();

        // Input 객체가 들고 있는 외부 byte[] 참조 해제
        input.setBuffer(new byte[0]);
    }

    /**
     * Kryo 객체를 반환할 때마다 Output과 Input을 초기화합니다.
     * 이를 통해 Kryo 객체가 풀에서 재사용될 때 이전 사용의 잔여 데이터를 제거하는 데 중요합니다.
     */
    public void resetForReturn() {
        output.setPosition(0);
        // output.clear();
        input.setBuffer(new byte[0]);
    }

}
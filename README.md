# KryoSerializer
Kryo 라이브러리를 기반으로 데이터 직렬화/역직렬화 작업을 수행합니다.

### Target
- 객체 생성 및 공유 없이 Static 생성자로 정의하여 간편한 사용성을 제공합니다.
- Kryo 객체 자체는 Thread-Safe 하지 않으므로, Wrapper 클래스로 감싸고 이를 동시성 큐 또는 풀 방식으로 관리하여 성능을 향상시킵니다.
- 객체 재사용(Output, Input) 및 버퍼 할당을 통해 GC 부담을 줄이고, 빠른 직렬화/역직렬화를 제공합니다.
- 보수적으로 포인터 및 버퍼를 초기화하여 안정성을 높입니다.

### 라이브러리 사용법
```java
import com.hooniegit.Xerializer.Kryo.ApachePoolSerializer;
import com.hooniegit.Xerializer.Kryo.PoolSerializer;
import com.resourcebox.KryoSerializer.QueueBasedSerializer;

SampleDataClass data = new SampleDataClass();

// Serialize
// DefaultPoolSerializer, ApachePoolSerializer, ThreadLocalSerializer 사용법은 동일
byte[] b = QueueBasedSerializer.serialize(data);

// Deserialize
SampleDataClass origin = QueueBasedSerializer.deserialize(b);
```


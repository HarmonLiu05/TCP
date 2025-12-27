# SR协议实现总结

## 一、核心实现要点

### 1. **窗口的数据结构**

**使用数组实现的循环队列**，严格按照PDF报告要求：

```java
// SenderWindow.java
private SenderElem[] window;  // 数组，大小固定为WINDOW_SIZE
private int size = 10;        // 窗口大小
private int base;             // 窗口基序号（最早未确认包）
private int nextToSend;       // 下一个待发送序号
private int rear;             // 队尾指针（下一个可用位置）

// 序列号到数组索引的映射
private int getIdx(int seq) {
    return seq % size;  // 关键：循环队列映射
}
```

**关键设计**：
- 使用 `seq % size` 将无限序列号空间映射到固定大小数组
- `base` 到 `rear` 之间是窗口内的包
- `nextToSend` 指向下一个要发送的包（可能在窗口内）

### 2. **窗口满阻塞应用层调用**

在 `rdt_send()` 中使用**自旋等待**实现流控：

```java
// TCP_Sender.java - rdt_send()
while (senderWindow.isFull()) {
    waitACK();           // 处理ACK释放空间
    Thread.yield();      // 让出CPU
}

// SenderWindow.java
public boolean isFull() {
    return (rear - base) >= size;  // 窗口内包数 >= 窗口大小
}
```

**流程**：
1. 应用层调用 `rdt_send()`
2. 检查窗口是否满
3. 满则循环等待，同时处理ACK
4. 窗口有空间后才继续

### 3. **窗口在确认后如何移动**

**只有base位置的包被确认才滑动**（SR协议关键）：

```java
// SenderWindow.java - slideWindow()
private void slideWindow() {
    while (base < rear) {
        int idx = getIdx(base);
        if (window[idx].isAcked()) {
            window[idx].reset();  // 清理元素和定时器
            base++;               // 滑动窗口
        } else {
            break;  // base未确认，停止滑动
        }
    }
}
```

**示例**：
```
窗口：[已确认, 已确认, 未确认, 已确认, ...]
       ↑base
滑动后：[未确认, 已确认, ...]
        ↑base（移动到第一个未确认）
```

### 4. **确认后的数据包是否去除**

**是的，确认并滑动后会清除**：

```java
// SenderElem.java - reset()
public void reset() {
    super.reset();       // 清除packet和flag
    if (timer != null) {
        timer.cancel();  // 取消定时器
        timer.purge();   // 清除已取消任务
        timer = null;
    }
}
```

**清理包括**：
- 数据包引用设为null
- 标志位重置
- 定时器取消并清除

### 5. **计时器数组如何生成和去除**

**每个窗口元素内置独立定时器**（不是数组）：

```java
// SenderElem.java
public class SenderElem extends WindowElem {
    private UDT_Timer timer;  // 每个元素一个定时器

    // 发送时启动
    public void scheduleTask(Client client, TCP_PACKET packet, long timeout) {
        if (timer != null) timer.cancel();  // 先取消旧的
        timer = new UDT_Timer();
        timer.schedule(new UDT_RetransTask(client, packet), timeout, timeout);
    }

    // 确认时去除
    public void ackPacket() {
        this.flag = ACKED;
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }
}
```

**生命周期**：
- **生成**：`sendPacket()` 时调用 `scheduleTask()`
- **去除**：收到ACK时调用 `ackPacket()`，或窗口滑动时调用 `reset()`

### 6. **SR是独立确认，不是累积确认**

SR协议**不使用累积确认**：

```java
// SenderWindow.java - ackPacket()
public void ackPacket(int seq) {
    // 遍历窗口查找对应序列号的包
    for (int i = base; i < rear; i++) {
        int idx = getIdx(i);
        if (window[idx].getPacket().getTcpH().getTh_seq() == seq) {
            if (!window[idx].isAcked()) {
                window[idx].ackPacket();  // 只确认这一个包
            }
            break;
        }
    }
    slideWindow();  // 尝试滑动（只有base确认才真正滑动）
}
```

**与GBN对比**：
- **GBN**：ACK n 表示 ≤n 的包都确认
- **SR**：ACK n 只确认包n，其他包状态不变

---

## 二、可靠传输三大问题处理

### 1. **位错（数据损坏）处理**

#### 实现机制：

```java
// TCP_Sender.java - 发送时计算校验和
tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));

// TCP_Receiver.java - 接收时验证
if (CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
    // 校验通过，处理包
    int result = receiverWindow.bufferPacket(recvPack);
    // ...
} else {
    // 校验失败，丢弃包，不回复ACK
    System.out.println("校验失败 - seq=" + recvPack.getTcpH().getTh_seq());
}
```

#### 处理流程：

```
发送方                          接收方
  |                              |
  |--[DATA seq=5, sum=0x1234]--->|
  |                              | 计算校验和
  |                              | 发现sum != 0x1234（位错）
  |                              | 丢弃，不回复ACK
  |                              |
  | 超时定时器触发（3秒）            |
  |                              |
  |--[重传DATA seq=5]----------->|
  |                              | 校验通过
  |<-----[ACK seq=5]-------------|
```

#### 结果验证：
日志中可以看到 `"校验失败"` 消息，对应的包会超时重传。

---

### 2. **丢包处理**

#### 实现机制：

```java
// SenderElem.java - 每个包独立定时器
public void scheduleTask(Client client, TCP_PACKET packet, long timeout) {
    timer = new UDT_Timer();
    // 周期性任务：超时未确认就重传
    timer.schedule(new UDT_RetransTask(client, packet), timeout, timeout);
}

// TCP_Sender.java - 收到ACK后取消定时器
public void recv(TCP_PACKET recvPack) {
    int ackSeq = recvPack.getTcpH().getTh_ack();
    senderWindow.ackPacket(ackSeq);  // 内部会取消对应定时器
}
```

#### 处理流程图：

```
时间线：
t=0     发送方发送 seq=10,20,30
        启动3个独立定时器

t=1     seq=10的ACK到达 ✓ → 取消timer_10
        seq=20丢包 ✗
        seq=30的ACK到达 ✓ → 取消timer_30

t=3     timer_20超时 → 只重传seq=20

t=4     seq=20的ACK到达 ✓ → 取消timer_20
```

#### 窗口状态演示：

```
发送后窗口：
[seq=10 未确认] [seq=20 未确认] [seq=30 未确认]
 ↑base

收到ACK 10和30后（20丢包）：
[seq=10 已确认] [seq=20 未确认] [seq=30 已确认]
 ↑base（不能滑动，因为base+1未确认）

seq=20重传并确认后：
[seq=10 已确认] [seq=20 已确认] [seq=30 已确认]
 ↑base
 滑动3次 → base变为31
```

#### 结果验证：
日志中 `*Re: DATA_seq: X` 表示重传，说明丢包检测和恢复正常工作。

---

### 3. **延迟（乱序）处理**

#### 实现机制：

```java
// ReceiverWindow.java - 缓存乱序包
public int bufferPacket(TCP_PACKET packet) {
    int seq = packet.getTcpH().getTh_seq();

    if (seq < base) {
        return DUPLICATE;  // 重复包（已交付）
    }
    if (seq >= base + size) {
        return UNORDERED;  // 窗口外（太超前）
    }

    int idx = getIdx(seq);
    window[idx].setPacket(packet);
    window[idx].setFlag(WindowElem.RECEIVED);

    if (seq == base) {
        return IS_BASE;  // 期望的包
    }
    return ORDERED;  // 乱序但在窗口内，缓存
}

// TCP_Receiver.java - 处理乱序包
int result = receiverWindow.bufferPacket(recvPack);
switch (result) {
    case ReceiverWindow.IS_BASE:
        // 期望的包，交付并尝试交付后续缓存包
        dataQueue.add(recvPack.getTcpS().getData());
        // 循环检查后续包
        while ((nextPacket = receiverWindow.getPacketToDeliver()) != null) {
            dataQueue.add(nextPacket.getTcpS().getData());
        }
        break;
    case ReceiverWindow.ORDERED:
        // 乱序包，已缓存，等待base到达
        System.out.println("SR缓存乱序包 - seq=" + seq);
        break;
}
// 无论如何都回复ACK
reply(ackPack);
```

#### 处理流程图：

```
场景：包按 30,10,20 顺序到达（期望顺序是10,20,30）

接收方状态演示：

1. 收到seq=30（乱序）
   窗口：[空] [空] [seq=30已缓存]
         ↑base=10
   操作：缓存，回复ACK 30，不交付

2. 收到seq=10（期望包）
   窗口：[seq=10] [空] [seq=30已缓存]
         ↑base=10
   操作：立即交付seq=10，base→20，回复ACK 10

3. 收到seq=20（填补空缺）
   窗口：[已交付] [seq=20] [seq=30已缓存]
                  ↑base=20
   操作：交付seq=20，检查到seq=30也在，连续交付20和30
         base→40，回复ACK 20
```

#### 缓冲区可视化：

```java
// ReceiverWindow.java - getPacketToDeliver()
public TCP_PACKET getPacketToDeliver() {
    int idx = getIdx(base);
    if (window[idx].getFlag() == WindowElem.RECEIVED) {
        TCP_PACKET packet = window[idx].getPacket();
        window[idx].reset();
        base++;  // 滑动
        return packet;
    }
    return null;  // base位置没有缓存包
}
```

#### 结果验证：
运行日志中可以看到：
```
SR缓存乱序包 - seq=25（后到）
SR接收 - seq=23 是期望的包（base）
SR交付 - seq=23
SR交付 - seq=24（之前缓存的）
SR交付 - seq=25（之前缓存的）
```

---

## 三、eflag测试配置说明

```java
// TCP_Sender.java
tcpH.setTh_eflag((byte)7);  // 当前设置
```

**eflag值含义**：
- **0**：无差错（理想信道）
- **4**：出错+丢包 
- **7**：出错+丢包+延迟（最严苛，会导致大量重传） ✅ **当前使用**

---

## 四、关键代码文件

| 文件 | 核心职责 |
|------|---------|
| `WindowElem.java` | 窗口元素基类 |
| `SenderElem.java` | 发送窗口元素+独立定时器 |
| `ReceiverElem.java` | 接收窗口元素 |
| `SenderWindow.java` | 发送窗口管理（循环队列） |
| `ReceiverWindow.java` | 接收窗口管理（乱序缓存） |
| `TCP_Sender.java` | 发送方协议逻辑 |
| `TCP_Receiver.java` | 接收方协议逻辑 |


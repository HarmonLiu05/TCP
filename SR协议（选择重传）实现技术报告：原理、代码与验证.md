---

# SR协议（选择重传）设计实现与实验验证报告

## 一、核心实现要点 (Implementation Details)

本部分详细阐述了 SR 协议底层的核心数据结构与逻辑实现，严格遵循实验要求。

### 1. 窗口的数据结构

**使用数组实现的循环队列**

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
- 使用 `seq % size` 将无限序列号空间映射到固定大小数组。
- `base` 到 `rear` 之间是窗口内的包。
- `nextToSend` 指向下一个要发送的包（可能在窗口内）。

### 2. 窗口满阻塞应用层调用

在 `rdt_send()` 中使用**自旋等待**实现流控，防止应用层发送过快溢出窗口：

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
1. 应用层调用 `rdt_send()`。
2. 检查窗口是否满。
3. 满则循环等待，同时调用 `waitACK()` 处理可能的确认。
4. 窗口有空间后才继续执行发送逻辑。

### 3. 窗口在确认后如何移动

**只有base位置的包被确认才滑动**（这是 SR 协议与 GBN 的核心区别）：

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

**滑动逻辑示例**：
```
初始窗口：[已确认, 已确认, 未确认, 已确认, ...]
           ↑base
滑动后：  [未确认, 已确认, ...]
           ↑base（移动到第一个未确认的位置）
```

### 4. 确认后的数据包是否去除

**是的，确认并滑动后会清除**，以释放资源：

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

**清理工作包括**：
- 数据包引用设为null。
- 标志位重置。
- **关键**：定时器取消并清除。

### 5. 计时器数组如何生成和去除

**每个窗口元素内置独立定时器**（非全局单一计时器）：

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
- **生成**：`sendPacket()` 时调用 `scheduleTask()`。
- **去除**：收到ACK时调用 `ackPacket()`，或窗口滑动时调用 `reset()`。

### 6. SR是独立确认，不是累积确认

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
- **GBN**：ACK n 表示 ≤n 的包都确认。
- **SR**：ACK n 只确认包n，其他包状态不变。

---

## 二、可靠传输三大问题处理 (Reliability Mechanisms)

### 1. 位错（数据损坏）处理

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
1.  发送方计算 Checksum。
2.  接收方校验。若校验失败（sum != 计算值），**直接丢弃**，不回复 ACK。
3.  发送方对应包的定时器超时，触发重传。

#### 结果验证：
日志中可以看到 `"校验失败"` 消息，对应的包会超时重传。

---

### 2. 丢包处理

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
t=0     发送方发送 seq=10, 20, 30 -> 启动3个独立定时器
t=1     seq=10 ACK到达 -> 取消timer_10
        seq=20 丢包 ✗
        seq=30 ACK到达 -> 取消timer_30
t=3     timer_20 超时 -> 只重传seq=20
t=4     seq=20 ACK到达 -> 取消timer_20
```

#### 窗口状态演示：
```
发送后窗口： [10(未)] [20(未)] [30(未)]
收到10,30后：[10(已)] [20(未)] [30(已)]  <- base指向20，无法滑动
20重传确认后：[10(已)] [20(已)] [30(已)]  <- 连续滑动，base跳至31
```

#### 结果验证：
日志中 `*Re: DATA_seq: X` 表示重传，说明丢包检测和恢复正常工作。

---

### 3. 延迟（乱序）处理

#### 实现机制：

```java
// ReceiverWindow.java - 缓存乱序包
public int bufferPacket(TCP_PACKET packet) {
    int seq = packet.getTcpH().getTh_seq();

    if (seq < base) return DUPLICATE;  // 重复包（已交付）
    if (seq >= base + size) return UNORDERED;  // 窗口外

    int idx = getIdx(seq);
    window[idx].setPacket(packet);
    window[idx].setFlag(WindowElem.RECEIVED);

    if (seq == base) return IS_BASE;  // 期望的包
    return ORDERED;  // 乱序但在窗口内，缓存
}

// TCP_Receiver.java - 处理乱序包
switch (result) {
    case ReceiverWindow.IS_BASE:
        // 交付当前包，并循环检查后续缓存包
        dataQueue.add(recvPack.getTcpS().getData());
        while ((nextPacket = receiverWindow.getPacketToDeliver()) != null) {
            dataQueue.add(nextPacket.getTcpS().getData());
        }
        break;
    case ReceiverWindow.ORDERED:
        // 乱序包，已缓存，等待base到达
        System.out.println("SR缓存乱序包 - seq=" + seq);
        break;
}
```

#### 处理流程与缓冲区可视化：

```java
// ReceiverWindow.java - getPacketToDeliver()
public TCP_PACKET getPacketToDeliver() {
    int idx = getIdx(base);
    // 只要当前base是已接收状态，就取出并滑动
    if (window[idx].getFlag() == WindowElem.RECEIVED) {
        TCP_PACKET packet = window[idx].getPacket();
        window[idx].reset();
        base++;  
        return packet;
    }
    return null;
}
```

---

## 三、日志分析与实验验证 (Log Analysis)

基于上述代码实现，我们通过实际运行日志来验证 SR 协议的核心功能。本次测试重点分析了 **Seq=300** 数据包在发生延迟（Delay）或丢失（Loss）时的系统行为。

### 1. 场景描述：模拟延迟/丢包
**现象**：Seq=300 的数据包在发送过程中出现严重延迟（模拟丢包），但发送方并未停止，而是继续发送后续的 Seq=301, 302 等数据包。

![Seq 300 Delay Scenario](./SR日志分析.assets/image-20251227143652751.png)

### 2. 验证点一：乱序缓存与独立确认
**目的**：证明接收方没有丢弃乱序包（区别于 GBN），且发送方正确记录了非连续的 ACK。

**日志定位**：`DATA_SEQ_309` 与 `ACK_309` 的处理部分。

![Out-of-Order Buffering](./SR日志分析.assets/image-20251227144252251.png)

**分析**：
*   **接收方**：日志显示 `SR接收 - seq=309 是乱序包（已缓存）`。证明 `ReceiverWindow.bufferPacket()` 逻辑生效，未丢弃包。
*   **发送方**：在处理 ACK 309 时，状态显示 `i=300... isAcked=false` 但 SR确认后,`i=309... isAcked=true`。
*   **窗口状态**：日志显示 `SR停止滑动 - base=300 未确认`。证明 `slideWindow()` 方法正确地被未确认的 base 阻塞。

### 3. 验证点二：超时重传与补全
**目的**：证明丢失的包（Seq 300）被独立定时器触发重传。

**日志定位**：时间戳 `14:17:53` 附近。

![Retransmission](./SR日志分析.assets/image-20251227144518500.png)

**分析**：
*   **重传发生**：日志显示 `DATA_SEQ_300 ... SR接收 - seq=300 是期望的包（base）`。
*   **时间验证**：上一条交互在 `14:17:50`，重传在 `14:17:53`，间隔约 **3秒**，精准匹配 `UDT_Timer` 的超时设置，证明 `SenderElem` 中的定时器独立工作正常。

### 4. 验证点三：批量交付与累积滑动 (Core Feature)
**目的**：证明 SR 协议的高效性——一旦缺口补齐，后续已缓存数据瞬间处理。

**日志定位**：紧接着重传成功后的处理。

![Batch Delivery](./SR日志分析.assets/image-20251227144659492.png)
*图：接收方批量交付*

![Window Sliding](./SR日志分析.assets/image-20251227144812630.png)
*图：发送方窗口滑动*

**分析**：
*   **批量交付**：接收方日志显示连续的 `SR交付 - seq=300` 到 `seq=309`。证明 `TCP_Receiver` 中的 `while` 循环正确提取了缓存数据。
*   **累积滑动**：发送方收到 `ACK_300` 后，日志显示 `>>> SR滑动完成 - 滑动了 10 个包`。`base` 从 300 直接跳变至 310。证明 `slideWindow()` 中的 `while (base < rear)` 循环正确执行。

---

## 四、配置与总结

### 1. eflag 测试配置
```java
// TCP_Sender.java
tcpH.setTh_eflag((byte)7);  // 7：出错+丢包+延迟（最严苛测试）
```
*注：日志中展示的主要是丢包/延迟场景，eflag=4 (出错+丢包) 或 eflag=7 均可触发该行为。*

### 2. 关键代码文件
| 文件                  | 核心职责                 |
| --------------------- | ------------------------ |
| `WindowElem.java`     | 窗口元素基类             |
| `SenderElem.java`     | 发送窗口元素+独立定时器  |
| `ReceiverElem.java`   | 接收窗口元素             |
| `SenderWindow.java`   | 发送窗口管理（循环队列） |
| `ReceiverWindow.java` | 接收窗口管理（乱序缓存） |
| `TCP_Sender.java`     | 发送方协议逻辑           |
| `TCP_Receiver.java`   | 接收方协议逻辑           |

### 3. 结论
通过上述代码原理分析与日志截图的互相印证，本系统实现了标准的 SR 协议，具备**独立确认、乱序缓存、选择重传**三大核心机制，能够在高误码、高丢包信道下保证数据的可靠有序传输。
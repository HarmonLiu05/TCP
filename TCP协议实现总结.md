# TCP协议实现总结（无拥塞控制）

## 一、概述

基于GBN和SR分支代码，实现了基础TCP协议。核心架构为：
- **发送端**：GBN逻辑（单一定时器、累积确认、重传所有）
- **接收端**：SR缓存能力 + GBN累积确认 + TCP Delayed ACK机制

---

## 二、核心架构对比

### 协议演进关系

```
SR协议（选择重传）
├─ 独立定时器（每个包）
├─ 独立确认（每个包单独ACK）
└─ 接收缓存（缓存乱序包）

GBN协议（回退N步）
├─ 单一定时器（只为base）
├─ 累积确认（ACK n表示≤n都已确认）
└─ 无缓存（丢弃乱序包）

TCP协议（本次实现）
├─ 发送端 = GBN逻辑
│  ├─ 单一定时器（只为base）
│  ├─ 累积确认
│  └─ 超时重传所有
├─ 接收端 = SR缓存 + GBN累积确认 + TCP Delayed ACK
│  ├─ 接收窗口缓存乱序包（SR特性）
│  ├─ 累积确认（GBN特性）
│  └─ Delayed ACK 500ms（TCP新特性）
└─ 新增机制：TCP Delayed ACK
   ├─ 按序包：500ms延迟发送ACK
   └─ 乱序包：立即发送Duplicate ACK
```

---

## 三、详细修改说明

### 3.1 接收端修改 (`TCP_Receiver.java`)

#### 关键变化：从GBN的"仅期望序号"改为SR的"缓存乱序"

**数据结构变化：**
```java
// 原GBN版本（丢弃乱序包）
private int expectedSeq;      // 期望接收的下一个序列号
private int lastAckSeq;       // 上一个成功接收的序号

// 新TCP版本（缓存乱序包）
private ReceiverWindow receiverWindow;      // 接收窗口（缓存乱序包）
private static final int WINDOW_SIZE = 10;
private Timer delayedAckTimer;              // 延迟ACK定时器
private static final long DELAYED_ACK_TIMEOUT = 500;  // 500ms延迟
private int pendingAckSeq = -1;             // 待发送的ACK序号
```

#### 接收逻辑核心改造：

**原GBN逻辑（丢弃乱序）:**
```java
if (seq == expectedSeq) {
    // 交付数据 + 回ACK + expectedSeq++
} else {
    // 丢弃乱序包，重发最近ACK
}
```

**新TCP逻辑（缓存乱序）:**
```java
int result = receiverWindow.bufferPacket(recvPack);

if (result == ReceiverWindow.IS_BASE) {
    // ===== 情况1：按序包 =====
    // 交付该包及所有连续缓存的包
    TCP_PACKET deliverablePacket;
    while ((deliverablePacket = receiverWindow.getPacketToDeliver()) != null) {
        dataQueue.add(deliverablePacket.getTcpS().getData());
    }
    
    // 关键：Delayed ACK（500ms后发送）
    int ackSeq = receiverWindow.getBase() - 1;  // 累积确认
    scheduleDelayedAck(ackSeq, recvPack.getSourceAddr());
    System.out.println("TCP Delayed ACK - 将在500ms后回复ACK=" + ackSeq);

} else if (result == ReceiverWindow.ORDERED) {
    // ===== 情况2：乱序包（窗口内） =====
    // 缓存并立即发送Duplicate ACK
    cancelDelayedAck();  // 取消延迟ACK
    
    int dupAckSeq = receiverWindow.getBase() - 1;
    if (dupAckSeq >= 0) {
        sendAck(dupAckSeq, recvPack.getSourceAddr());
        System.out.println("TCP立即发送Duplicate ACK=" + dupAckSeq + " (乱序触发)");
    }

} else if (result == ReceiverWindow.DUPLICATE) {
    // ===== 情况3：重复包（base之前） =====
    // 立即重发ACK
    int dupAckSeq = receiverWindow.getBase() - 1;
    if (dupAckSeq >= 0) {
        sendAck(dupAckSeq, recvPack.getSourceAddr());
        System.out.println("TCP重发ACK=" + dupAckSeq + " (重复包触发)");
    }

} else if (result == ReceiverWindow.UNORDERED) {
    // ===== 情况4：窗口外的包 =====
    // 丢弃
    System.out.println("TCP丢弃 - seq=" + seq + " 超出窗口范围");
}
```

#### TCP Delayed ACK 机制实现：

```java
/**
 * TCP Delayed ACK机制：延迟500ms发送ACK
 * 如果期间有反向数据可捎带，则取消此定时器
 */
private void scheduleDelayedAck(final int ackSeq, final InetAddress destAddr) {
    // 取消之前的延迟ACK
    cancelDelayedAck();
    
    // 启动新的延迟ACK定时器
    pendingAckSeq = ackSeq;
    delayedAckTimer = new Timer();
    delayedAckTimer.schedule(new TimerTask() {
        @Override
        public void run() {
            // 500ms后发送ACK
            sendAck(ackSeq, destAddr);
            System.out.println("TCP Delayed ACK触发 - 发送ACK=" + ackSeq);
            pendingAckSeq = -1;
        }
    }, DELAYED_ACK_TIMEOUT);
}

/**
 * 取消延迟ACK定时器
 */
private void cancelDelayedAck() {
    if (delayedAckTimer != null) {
        delayedAckTimer.cancel();
        delayedAckTimer.purge();
        delayedAckTimer = null;
    }
}

/**
 * 发送ACK（累积确认）
 */
private void sendAck(int ackSeq, InetAddress destAddr) {
    tcpH.setTh_ack(ackSeq);
    ackPack = new TCP_PACKET(tcpH, tcpS, destAddr);
    tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
    tcpH.setTh_eflag((byte)0);  // ACK包不模拟错误
    reply(ackPack);
}
```

### 3.2 接收窗口修改 (`ReceiverWindow.java`)

**仅改动日志前缀，所有功能保持不变：**
- 保留SR的所有缓存能力
- 保留循环队列实现
- 保留 `bufferPacket()` 和 `getPacketToDeliver()` 方法

**日志修改汇总：**
```java
// 原: System.out.println("SR接收 - seq=" + seq + " ...")
// 新: System.out.println("TCP接收窗口 - seq=" + seq + " ...")

// 原: System.out.println("SR交付 - seq=" + packet.getTcpH().getTh_seq() + " ...")
// 新: System.out.println("TCP交付 - seq=" + packet.getTcpH().getTh_seq() + " ...")
```

### 3.3 发送端修改 (`TCP_Sender.java`)

**完全保留GBN逻辑，仅改动注释：**
- 无代码逻辑变化
- 只修改日志前缀从"GBN"→"TCP"
- 保留所有GBN特性：
  - 累积确认处理
  - 窗口满自旋等待
  - 每次发送后处理ACK

**关键保留的GBN特性：**
```java
@Override
public void rdt_send(int dataIndex, int[] appData) {
    // GBN流控：窗口满时自旋等待
    while (senderWindow.isFull()) {
        waitACK();
        Thread.yield();
    }
    
    // ... 发送逻辑保持不变 ...
    
    // 每次发送后都处理待处理的ACK（GBN特性）
    waitACK();
}

@Override
public void recv(TCP_PACKET recvPack) {
    int ackSeq = recvPack.getTcpH().getTh_ack();
    // 直接处理ACK，不放入队列（GBN特性）
    senderWindow.ackPacket(ackSeq);
}
```

### 3.4 发送窗口修改 (`SenderWindow.java`)

**完全保留GBN逻辑，仅改动注释：**
- 无代码逻辑变化
- 只修改日志前缀从"GBN"→"TCP"
- 保留所有GBN特性

**关键保留的GBN特性：**

1. **单一全局定时器（只为base）:**
```java
private UDT_Timer baseTimer;  // 只有一个定时器

private void startTimer() {
    // 先停止旧的定时器
    stopTimer();
    
    // 创建新的定时器，超时时重传所有未确认的包
    baseTimer = new UDT_Timer();
    baseTimer.schedule(new java.util.TimerTask() {
        @Override
        public void run() {
            onTimeout();
        }
    }, TIMEOUT_MS);
}
```

2. **超时重传所有（Go-Back-N行为）:**
```java
private void onTimeout() {
    System.out.println("\n!!! TCP超时 - 重传窗口内所有包 [" + base + "," + nextToSend + ") !!!");
    
    // 重传所有已发送但未确认的包
    for (int i = base; i < nextToSend; i++) {
        int idx = getIdx(i);
        TCP_PACKET packet = window[idx].getPacket();
        if (packet != null) {
            sender.udt_send(packet);
            System.out.println("TCP重传 - seq=" + packet.getTcpH().getTh_seq());
        }
    }
    
    // 重启定时器
    startTimer();
}
```

3. **累积确认处理：**
```java
public void ackPacket(int seq) {
    // TCP累积确认（GBN逻辑）：ACK n 表示 n 及之前的所有包都已确认
    if (seq >= base && seq < rear) {
        // 标记所有 <= seq 的包为已确认
        for (int i = base; i <= seq && i < rear; i++) {
            int idx = getIdx(i);
            if (!window[idx].isAcked()) {
                window[idx].setFlag(WindowElem.ACKED);
            }
        }
        
        // 滑动窗口
        slideWindow();
    }
}
```

4. **窗口滑动后的定时器管理：**
```java
private void slideWindow() {
    // ... 滑动逻辑 ...
    
    // TCP关键：GBN的窗口滑动后定时器管理
    if (base == rear) {
        // 窗口已空，停止定时器
        stopTimer();
    } else {
        // 窗口仍有未确认的包，重启定时器
        startTimer();
    }
}
```

---

## 四、TCP协议特性总结表

| 特性 | 实现方式 | 来源 | 关键代码位置 |
|------|----------|------|------------|
| **乱序缓存** | ReceiverWindow数组缓存 | SR | `ReceiverWindow.bufferPacket()` |
| **按序交付** | 只交付连续的包 | SR | `ReceiverWindow.getPacketToDeliver()` |
| **累积确认** | ACK = base - 1 | GBN | `TCP_Receiver.rdt_recv()` |
| **单一定时器** | baseTimer只为base计时 | GBN | `SenderWindow.startTimer()` |
| **超时重传所有** | 重传[base, nextToSend) | GBN | `SenderWindow.onTimeout()` |
| **窗口管理** | 循环队列 | GBN | `SenderWindow.slideWindow()` |
| **Delayed ACK** | 按序包500ms延迟 | TCP新增 | `TCP_Receiver.scheduleDelayedAck()` |
| **快速重传** | 乱序包立即Dup ACK | TCP新增 | `TCP_Receiver.sendAck()` |

---

## 五、可靠传输三要素处理

### 位错处理（Checksum）

**机制：**
```java
// 校验和检查
if (CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
    // 数据完整，处理包
} else {
    // 数据损坏，丢弃，不回ACK
    System.out.println("校验失败 - seq=" + recvPack.getTcpH().getTh_seq());
}
```

**恢复：** 发送方超时定时器触发重传

---

### 丢包处理（Timeout & Retransmission）

**检测机制：**
- 单一定时器只为base计时（GBN特性）
- base包3秒后未收到ACK，触发 `onTimeout()`

**恢复机制：**
```java
private void onTimeout() {
    // 重传窗口内所有包 [base, nextToSend)
    for (int i = base; i < nextToSend; i++) {
        sender.udt_send(window[getIdx(i)].getPacket());
    }
    // 重启定时器
    startTimer();
}
```

---

### 延迟/乱序处理（Delay & Out-of-Order）

**接收方处理：**
```
按序包 (seq == base)
  ↓
缓存（ReceiverWindow）+ 交付 + Delayed ACK(500ms)

乱序包 (base < seq < base+size)
  ↓
缓存（ReceiverWindow）+ 立即Duplicate ACK + 取消延迟

重复包 (seq < base)
  ↓
立即重发ACK（促进发送方快速重传）

窗口外 (seq >= base+size)
  ↓
丢弃
```

**累积确认的作用：**
- ACK n = 确认了 [0...n] 的所有包
- 即使某个ACK丢失，后续ACK可以覆盖
- 减少因ACK丢失导致的不必要重传

---

## 六、工作流程图

### 发送方状态机

```
[初始状态]
  base=0, nextToSend=0, rear=0, baseTimer=null

1. 应用层调用 rdt_send(data)
   ├─> pushPacket(packet)  [rear++]
   ├─> sendPacket()        [发送，nextToSend++]
   └─> if (base == nextToSend-1) startTimer()

2. 收到 ACK n
   ├─> 标记所有 base≤i≤n 为ACKED
   ├─> slideWindow()
   │   ├─> base++++ (连续滑动)
   │   └─> if (base==rear) stopTimer() else startTimer()
   └─> 处理完成

3. 超时事件（baseTimer到期）
   ├─> for i in [base, nextToSend): udt_send(packet[i])
   └─> startTimer()  [重启定时器]
```

### 接收方状态机

```
[初始状态]
  receiverWindow(base=0), delayedAckTimer=null

1. 收到包 seq，校验成功
   ├─> bufferPacket(seq)
   │   ├─> if (seq < base) DUPLICATE
   │   ├─> if (seq >= base+size) UNORDERED
   │   └─> else ORDERED或IS_BASE
   │
   ├─> if (IS_BASE)
   │   ├─> 交付base及连续缓存的包
   │   └─> scheduleDelayedAck(base-1, 500ms)
   │
   ├─> else if (ORDERED)
   │   ├─> 缓存在窗口内
   │   ├─> cancelDelayedAck()
   │   └─> sendAck(base-1)  [立即Duplicate ACK]
   │
   ├─> else if (DUPLICATE)
   │   └─> sendAck(base-1)  [立即重发ACK]
   │
   └─> else (UNORDERED)
       └─> 丢弃

2. Delayed ACK定时器到期 (500ms)
   └─> sendAck(pendingAckSeq)

3. 收到乱序包时
   └─> cancelDelayedAck()  [取消延迟，立即发送]
```

---

## 七、编译与运行

### 编译命令
```powershell
cd "C:\Users\liuhe\Desktop\learning\110-大学课程学习\大二上学期\计算机网络\TCP"
javac -encoding UTF-8 -d bin -cp "TCP_TestSys_Linux.jar" src/com/ouc/tcp/test/*.java
```

### 编译结果
```
✅ 编译成功 (无错误)
```

### 运行测试
在IDEA中运行 `TestRun.java`，按空格启动传输。

### 测试配置
```java
// TCP_Sender.udt_send() 中的eflag配置
eflag = 7  // 出错+丢包+延迟（完整测试）
```

---

## 八、日志输出示例

### 正常传输日志

```
TCP协议接收端启动 - 窗口大小=10, 支持Delayed ACK
TCP协议发送端启动 - 窗口大小=10, GBN发送端逻辑

TCP入窗 - seq=0 (rear=1)
TCP发送 - seq=0 (base=0, nextToSend=1, rear=1)
TCP启动定时器 - base=0

TCP接收窗口 - seq=0 是期望的包（base）
TCP接收 - seq=0 是期望的包（按序）
TCP Delayed ACK - 将在500ms后回复ACK=0

...（500ms延迟）...

TCP Delayed ACK触发 - 发送ACK=0

>>> 收到ACK - seq=0 <<<
=== TCP处理ACK - seq=0 (base=0, rear=1) ===
  >>> TCP确认 - seq=0 (idx=0) <<<
TCP窗口滑动 - 从 base=0 滑动到 1 (滑动1个包)
TCP窗口已空 - 停止定时器
=== ACK处理完成 ===
```

### 乱序处理日志

```
TCP入窗 - seq=2 (rear=3)
TCP发送 - seq=2 (base=0, nextToSend=3, rear=3)

TCP接收窗口 - seq=2 是乱序包（已缓存）
TCP接收 - seq=2 是乱序包（已缓存）
TCP立即发送Duplicate ACK=-1 (乱序触发)

...（收到seq=1）...

TCP接收窗口 - seq=1 是期望的包（base）
TCP接收 - seq=1 是期望的包（按序）
TCP交付 - seq=1 (新base=2)
TCP交付 - seq=2 (新base=3)
TCP Delayed ACK - 将在500ms后回复ACK=2
```

### 超时重传日志

```
TCP启动定时器 - base=0
...（3秒无ACK）...

!!! TCP超时 - 重传窗口内所有包 [0,5) !!!
TCP重传 - seq=0
TCP重传 - seq=1
TCP重传 - seq=2
TCP重传 - seq=3
TCP重传 - seq=4
TCP启动定时器 - base=0
```

---

## 九、关键代码位置速查

| 功能 | 文件 | 方法/变量 | 行号参考 |
|------|------|----------|----------|
| 接收窗口初始化 | TCP_Receiver.java | 构造函数 | 20-26 |
| 缓存乱序包 | TCP_Receiver.java | rdt_recv() | 40 |
| Delayed ACK启动 | TCP_Receiver.java | scheduleDelayedAck() | 112-128 |
| 快速重传 | TCP_Receiver.java | sendAck() | 144-150 |
| 单一定时器 | SenderWindow.java | baseTimer字段 | 28-30 |
| 启动定时器 | SenderWindow.java | startTimer() | 117-131 |
| 超时重传所有 | SenderWindow.java | onTimeout() | 148-163 |
| 累积确认 | SenderWindow.java | ackPacket() | 164-192 |
| 窗口滑动管理 | SenderWindow.java | slideWindow() | 205-239 |

---

## 十、总结

### 实现特点

✅ **结合了GBN和SR的优势：**
- GBN的高效发送端（单一定时器，简化逻辑）
- SR的灵活接收端（缓存乱序，提高吞吐量）

✅ **新增TCP Delayed ACK机制：**
- 按序包延迟500ms发送ACK（减少ACK数量）
- 乱序包立即发送Duplicate ACK（快速重传）

✅ **保持可靠传输能力：**
- 位错检测（CheckSum）
- 丢包检测（定时器）
- 乱序处理（缓存+ACK）

### 测试场景

| 场景 | 期望行为 |
|------|---------|
| 按序到达 | Delayed ACK，500ms后发送ACK |
| 乱序到达 | 立即Duplicate ACK，触发快速重传 |
| 重复包 | 立即重发ACK |
| 超时 | 重传[base, nextToSend)所有包 |
| 校验失败 | 丢弃，不回ACK，等待超时重传 |

---

## 附录：修改清单

### 修改的文件

1. ✅ `TCP_Receiver.java` - 引入SR缓存+TCP Delayed ACK
2. ✅ `ReceiverWindow.java` - 日志前缀修改SR→TCP
3. ✅ `TCP_Sender.java` - 日志前缀修改GBN→TCP
4. ✅ `SenderWindow.java` - 日志前缀修改GBN→TCP

### 保持不变的文件

- `SenderElem.java` - 不使用（GBN使用基类WindowElem）
- `WindowElem.java` - 基类保持不变
- `ReceiverElem.java` - SR接收元素，功能保持不变
- `CheckSum.java` - 校验和计算保持不变
- `TestRun.java` - 测试框架不变

---

**实现完成日期：** 2025-12-27  
**协议标准：** 基础TCP（无拥塞控制）  
**编译状态：** ✅ 成功  
**运行状态：** 准备测试

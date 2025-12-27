# GBN (Go-Back-N) 协议实现说明

## 一、从SR协议到GBN协议的核心改造

本次修改将**SR (Selective Repeat) 选择重传协议**改造为**标准的GBN (Go-Back-N) 回退N步协议**。

### 核心差异对比

| 特性 | SR协议 | GBN协议 |
|------|--------|---------|
| **接收方缓存** | 有缓存,可接收乱序包 | **无缓存,只接收期望序号** |
| **确认机制** | 独立确认(每个包单独ACK) | **累积确认(ACK n表示≤n都已收到)** |
| **定时器** | 每个包独立定时器 | **单一全局定时器(只为base)** |
| **超时重传** | 只重传超时的包 | **重传窗口内所有未确认包** |
| **窗口滑动** | base确认后滑动 | base确认后累积滑动 |

---

## 二、主要修改文件

### 1. `TCP_Receiver.java` - 接收方核心修改

#### 关键变化:
```java
// SR版本: 维护接收窗口,缓存乱序包
private ReceiverWindow receiverWindow;
private static final int WINDOW_SIZE = 10;

// GBN版本: 只维护期望序号,无缓存
private int expectedSeq;  // 期望接收的下一个序列号
private int lastAckSeq;   // 上一个成功接收的序号(用于重发ACK)
```

#### 接收逻辑(`rdt_recv`方法):
```java
if (seq == expectedSeq) {
    // ✅ 收到期望的包
    // 1. 交付数据到应用层
    dataQueue.add(recvPack.getTcpS().getData());
    
    // 2. 回复ACK(累积确认)
    tcpH.setTh_ack(seq);
    reply(ackPack);
    
    // 3. 更新期望序号
    lastAckSeq = seq;
    expectedSeq++;
    
} else {
    // ❌ 收到乱序包或重复包 - GBN直接丢弃
    // 关键: 重发最近一次成功接收的ACK (lastAckSeq)
    if (lastAckSeq >= 0) {
        tcpH.setTh_ack(lastAckSeq);
        reply(ackPack);
    }
}
```

**GBN接收方行为总结**:
- ✅ **期望包**: 交付→回ACK→窗口前移
- ❌ **乱序包**: 丢弃→重发最近ACK (触发发送方快速重传)
- ❌ **重复包**: 丢弃→重发ACK

---

### 2. `SenderWindow.java` - 发送窗口核心修改

#### 关键变化1: 数据结构
```java
// SR版本: 每个元素有独立定时器
private SenderElem[] window;  // SenderElem继承自WindowElem,增加timer

// GBN版本: 使用基类,单一全局定时器
private WindowElem[] window;  // 基类WindowElem,不含timer
private UDT_Timer baseTimer;   // 单一全局定时器
private TCP_Sender sender;     // 用于超时重传时调用udt_send
```

#### 关键变化2: 定时器管理
```java
/**
 * 启动全局定时器 - GBN关键
 * 只有一个定时器,用于base位置的包
 */
private void startTimer() {
    stopTimer();  // 先停止旧的
    
    baseTimer = new UDT_Timer();
    baseTimer.schedule(new TimerTask() {
        @Override
        public void run() {
            onTimeout();  // 超时时重传所有
        }
    }, TIMEOUT_MS);
}

/**
 * 超时处理 - GBN关键: 重传所有未确认的包
 */
private void onTimeout() {
    // 重传 [base, nextToSend) 区间的所有包
    for (int i = base; i < nextToSend; i++) {
        int idx = getIdx(i);
        TCP_PACKET packet = window[idx].getPacket();
        if (packet != null) {
            sender.udt_send(packet);
        }
    }
    startTimer();  // 重启定时器
}
```

**定时器状态机**:
1. **发送base包时**: 启动定时器
2. **收到ACK,窗口滑动后**:
   - 窗口空 → 停止定时器
   - 窗口非空 → **重启定时器**(重新为新base计时)
3. **超时**: 重传所有 → 重启定时器

#### 关键变化3: 累积确认
```java
/**
 * 处理ACK - GBN关键: 累积确认
 * 收到ACK n,表示 n 及之前的所有包都已确认
 */
public void ackPacket(int seq) {
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

**SR vs GBN确认对比**:
- **SR**: 遍历窗口,找到seq匹配的那一个包,标记为ACKED
- **GBN**: 直接标记`base`到`seq`之间的所有包为ACKED (累积确认)

#### 关键变化4: 窗口滑动后的定时器管理
```java
private void slideWindow() {
    // ... 滑动逻辑 ...
    
    if (slideCount > 0) {
        // GBN关键: 窗口滑动后的定时器管理
        if (base == rear) {
            // 窗口已空,停止定时器
            stopTimer();
        } else {
            // 窗口仍有未确认的包,重启定时器
            startTimer();
        }
    }
}
```

---

### 3. `TCP_Sender.java` - 发送方修改

主要是构造函数传递`this`引用:
```java
public TCP_Sender() {
    super();
    super.initTCP_Sender(this);
    // GBN需要将sender传给窗口,用于超时重传
    senderWindow = new SenderWindow(WINDOW_SIZE, client, this);
}
```

---

## 三、GBN协议工作流程

### 发送方状态机

```
[初始状态] base=0, nextToSend=0, rear=0, timer=null

1. 应用层调用 rdt_send(data)
   ├─> pushPacket(packet)  # rear++
   ├─> sendPacket()        # 发送,nextToSend++
   └─> if (base == nextToSend-1) startTimer()  # 发送base时启动

2. 收到 ACK n
   ├─> 标记 base~n 为 ACKED
   ├─> slideWindow()  # 连续滑动已确认的包
   └─> if (base < rear) startTimer() else stopTimer()

3. 超时事件
   ├─> for i in [base, nextToSend): udt_send(packet[i])
   └─> startTimer()  # 重启定时器
```

### 接收方状态机

```
[初始状态] expectedSeq=0, lastAckSeq=-1

1. 收到包 seq
   ├─> 校验和检查
   │   └─> 失败: 丢弃,不回ACK
   └─> 成功:
       ├─> if (seq == expectedSeq):
       │   ├─> deliver_data()    # 交付应用层
       │   ├─> reply(ACK seq)    # 回复ACK
       │   ├─> lastAckSeq = seq
       │   └─> expectedSeq++     # 期望下一个
       └─> else:  # 乱序包/重复包
           └─> reply(ACK lastAckSeq)  # 重发最近ACK
```

---

## 四、GBN协议核心特性验证

### 1. 位错处理 (Bit Error)
- **机制**: CheckSum校验和
- **接收方**: 校验失败→丢弃→不回ACK
- **发送方**: 超时→重传所有未确认包

### 2. 丢包处理 (Packet Loss)
- **数据包丢失**: 
  - 发送方超时→重传窗口内所有包
- **ACK丢失**:
  - 累积确认机制: 后续ACK可以确认之前的包
  - 示例: ACK2丢失,ACK3到达可确认0,1,2,3

### 3. 延迟/乱序处理 (Delay/Out-of-Order)
- **接收方**: 只接收expectedSeq,乱序包一律丢弃
- **发送方**: 通过重传解决乱序问题
- **累积确认**: 一个ACK可确认多个包,减少ACK丢失影响

---

## 五、运行测试

### 编译
```powershell
cd "C:\Users\liuhe\Desktop\learning\110-大学课程学习\大二上学期\计算机网络\TCP"
javac -encoding UTF-8 -d bin -cp "TCP_TestSys_Linux.jar" src/com/ouc/tcp/test/*.java
```

### 运行
在IDEA中运行 `TestRun.java`,按空格启动传输。

### 测试参数(`TCP_Sender.udt_send`):
- `eflag = 0`: 无差错 (快速测试)
- `eflag = 4`: 出错+丢包 (中等测试)
- `eflag = 7`: 出错+丢包+延迟 (完整测试,会很慢)

当前配置: `eflag = 7` (完整测试)

---

## 六、GBN vs SR 性能对比

| 指标 | GBN | SR |
|------|-----|-----|
| **实现复杂度** | 简单 | 复杂 |
| **接收方缓存** | 不需要 | 需要 |
| **定时器开销** | 1个定时器 | N个定时器 |
| **重传效率** | 低(重传所有) | 高(只重传丢失包) |
| **信道利用率** | 低(丢包时大量重传) | 高(选择性重传) |
| **适用场景** | 低误码率信道 | 高误码率信道 |

**结论**: GBN实现简单,但重传开销大; SR实现复杂,但信道利用率高。

---

## 七、关键代码位置总结

| 文件 | 关键修改点 | 行号参考 |
|------|------------|----------|
| `TCP_Receiver.java` | expectedSeq维护 | 18-20 |
| | rdt_recv逻辑 | 33-93 |
| `SenderWindow.java` | 单一定时器 | 28-31 |
| | 构造函数 | 39-53 |
| | sendPacket定时器管理 | 91-111 |
| | startTimer/stopTimer | 117-143 |
| | onTimeout重传所有 | 148-163 |
| | ackPacket累积确认 | 164-192 |
| | slideWindow定时器管理 | 205-239 |
| `TCP_Sender.java` | 构造函数传this | 18-24 |

---

## 八、注意事项

1. **不要修改ReceiverWindow.java**: GBN接收方不使用此类
2. **不要修改SenderElem.java**: GBN使用基类WindowElem
3. **定时器管理**: 窗口滑动后务必重启定时器(如果窗口非空)
4. **累积确认**: ACK n表示≤n的所有包都已确认,不是只确认n
5. **重传策略**: 超时时必须重传[base, nextToSend)的所有包

---

## 九、验证要点

运行后检查日志,确认以下GBN特性:
- ✅ 接收方日志显示"GBN丢弃 - seq=X 是乱序包"
- ✅ 接收方重发ACK: "重发ACK - seq=X (期望=Y)"
- ✅ 发送方超时: "GBN超时 - 重传窗口内所有包 [base, nextToSend)"
- ✅ 累积确认: "GBN确认 - seq=0", "seq=1", ... (连续多个)
- ✅ 定时器管理: "GBN启动定时器"/"GBN停止定时器"

---

**修改完成时间**: 2025-12-27  
**协议标准**: 教科书标准GBN (接收方无缓存)

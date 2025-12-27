好的！我来实现 RDT 3.0 协议。RDT 3.0 的核心是**增加超时重传机制来处理丢包问题**。

让我先查看当前代码，然后实现 RDT 3.0：


**已查看文件** *TCP_Sender.java*

**已查看文件** *TCP_Receiver.java*


现在我来实现 RDT 3.0。核心是**增加超时重传机制**来处理丢包：


[TCP_Sender.java](C:\Users\liuhe\Desktop\learning\110-大学课程学习\大二上学期\计算机网络\TCP\src\com\ouc\tcp\test\TCP_Sender.java)


现在修改接收方，使其也能处理 RDT 3.0 的场景（虽然接收方改动较小）：


[TCP_Receiver.java](C:\Users\liuhe\Desktop\learning\110-大学课程学习\大二上学期\计算机网络\TCP\src\com\ouc\tcp\test\TCP_Receiver.java)


完美！现在让我总结 RDT 3.0 的核心改动和原理：

---

## ✅ RDT 3.0 实现完成

## 📊 核心改动总结

### 🎯 **RDT 3.0 的核心目标**
**处理丢包问题**，通过**超时重传机制**实现可靠传输

---

## 🔧 具体改动

### 1️⃣ **发送方 (TCP_Sender.java) - 5大改动**

#### **改动1：新增超时定时器**
```java
private UDT_Timer timer;  // 超时重传定时器
private int lastAckReceived = 0;  // 记录上次收到的ACK号
```


#### **改动2：rdt_send() - 启动定时器**
```java
// 发送数据包后，立即启动定时器
timer = new UDT_Timer();
timer.schedule(new UDT_RetransTask(client, tcpPack), 3000, 3000);
// 3秒超时，每3秒重传一次

// 收到ACK后停止定时器
timer.cancel();
```


#### **改动3：eflag 设置改为 2（丢包模式）**
```java
// RDT 2.0/2.2: eflag=1（只出错）
// RDT 3.0: eflag=2（只丢包）← 核心改动
tcpH.setTh_eflag((byte)2);
```


#### **改动4：recv() - 简化ACK处理**
```java
if (receivedAck == currentSeq) {
    // 收到正确ACK
    timer.cancel();  // ← 关键：停止定时器
    flag = 1;
} else {
    // 收到重复/旧ACK，不做处理
    // 等待超时重传（由定时器自动触发）
}
```


#### **改动5：移除立即重传逻辑**
- ❌ RDT 2.0：收到 NACK → 立即重传
- ❌ RDT 2.2：收到重复 ACK → 立即重传
- ✅ RDT 3.0：收到错误反馈 → **等待超时**，由定时器触发重传

---

### 2️⃣ **接收方 (TCP_Receiver.java) - 2大改动**

#### **改动1：新增变量**
```java
int lastCorrectSeq = 0;  // 记录上一个正确接收的包序号
```


#### **改动2：rdt_recv() - 发送重复ACK**
```java
if (校验和正确) {
    tcpH.setTh_ack(recvPack.getTh_seq());
    lastCorrectSeq = recvPack.getTh_seq();  // 更新
    reply(ackPack);
} else {
    // 不发送NACK，发送重复ACK
    tcpH.setTh_ack(lastCorrectSeq);  // 上一个正确的ACK
    tcpH.setTh_eflag((byte)0);
    reply(ackPack);
}
```


---

## 🔬 改动原理详解

### 📌 **RDT 3.0 的三大核心机制**

| 机制         | RDT 2.0                | RDT 2.2             | RDT 3.0        |
| ------------ | ---------------------- | ------------------- | -------------- |
| **位错处理** | NACK (ack=-1, eflag=1) | 重复ACK             | 重复ACK        |
| **丢包处理** | ❌ 无法处理             | ❌ 无法处理          | ✅ **超时重传** |
| **触发重传** | 立即（收到NACK）       | 立即（收到重复ACK） | **定时器超时** |

---

### 🎯 **原理1：超时重传机制**

```
发送方：
  1. 发送数据包 seq=101
  2. 启动定时器（3秒）
  3. 等待ACK
  
┌─────────────────┬─────────────────────┐
│ ACK正常到达     │ ACK/数据包丢失      │
└─────────────────┴─────────────────────┘
       ↓                      ↓
  收到 ACK=101          3秒内无ACK
  停止定时器            定时器超时！
  flag=1               ↓
  发送下一包           自动重传 seq=101
                       重新启动定时器
                       继续等待...
```


**关键点**：
- ✅ 不依赖接收方的反馈来触发重传
- ✅ 即使 ACK 丢失，也能通过超时重传恢复
- ✅ 定时器自动处理，发送方 `recv()` 逻辑简化

---

### 🎯 **原理2：eflag=2 丢包模式**

```java
// RDT 2.0/2.2: eflag=1
模拟器：随机损坏数据包的校验和（位错误）
结果：接收方检测到错误 → 发送NACK/重复ACK

// RDT 3.0: eflag=2
模拟器：随机丢弃数据包或ACK包（丢包）
结果：发送方等待超时 → 定时器触发重传
```


**eflag=2 的作用**：
- 数据包可能丢失 → 接收方收不到
- ACK 可能丢失 → 发送方收不到反馈
- **只能通过超时检测**

---

### 🎯 **原理3：定时器工作流程**

```java
// 创建定时器
timer = new UDT_Timer();

// 调度重传任务
timer.schedule(
    new UDT_RetransTask(client, tcpPack),  // 重传任务
    3000,  // 首次延迟：3秒后执行
    3000   // 周期：每3秒重传一次
);

// 收到ACK后停止
timer.cancel();
```


**定时器三种结局**：
1. ✅ **正常情况**：3秒内收到ACK → `timer.cancel()` → 停止
2. ⏰ **超时重传**：3秒无ACK → 定时器触发 → 自动重传
3. 🔁 **持续重传**：每3秒重传一次，直到收到ACK

---

## 🆚 RDT 版本对比总结

| 特性         | RDT 2.0   | RDT 2.2     | RDT 3.0         |
| ------------ | --------- | ----------- | --------------- |
| **位错检测** | ✅ 校验和  | ✅ 校验和    | ✅ 校验和        |
| **位错反馈** | NACK      | 重复ACK     | 重复ACK         |
| **丢包检测** | ❌         | ❌           | ✅ **超时**      |
| **重传触发** | 收到NACK  | 收到重复ACK | **定时器超时**  |
| **eflag**    | 1（出错） | 1（出错）   | **2（丢包）**   |
| **定时器**   | ❌         | ❌           | ✅ **UDT_Timer** |
| **处理场景** | 位错      | 位错        | **位错+丢包**   |

---

## 🔑 RDT 3.0 的关键创新

1. **不依赖立即反馈**
   - RDT 2.0/2.2：必须等接收方反馈（NACK/重复ACK）
   - RDT 3.0：即使没有反馈，超时也会触发重传

2. **处理所有传输错误**
   - 数据包丢失 ✅
   - ACK 丢失 ✅
   - 数据包损坏 ✅（继承自 RDT 2.2）

3. **自动化重传**
   - 定时器自动调度，无需手动检测

---

## ✅ 验证要点

运行程序后应该看到：

**正常情况**：
```
RDT3.0 - ACK for seq: 101
RDT3.0 - Receive ACK: 101 (Current seq: 101)
RDT3.0 - Correct ACK, Clear: 101
```


**丢包+超时重传**：
```
// 发送 seq=201，但包丢失
// 3秒后超时，定时器自动重传
// 重传成功，收到ACK
RDT3.0 - Receive ACK: 201 (Current seq: 201)
RDT3.0 - Correct ACK, Clear: 201
```


这就是 RDT 3.0 的完整实现和原理！🎉
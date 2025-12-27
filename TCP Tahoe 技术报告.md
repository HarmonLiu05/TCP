---

# TCP Tahoe 可靠传输协议设计与实现技术报告

**项目名称**：TCP 协议实现（Tahoe 版本）
**实现基础**：GBN 发送端 + SR 接收端（单计时器架构）
**重构重点**：拥塞控制、动态窗口管理、累积确认修正

---

## 1. 概述

本实验在基础 TCP（GBN Sender + SR Receiver）的分支上，重构了发送端核心逻辑以实现 **TCP Tahoe** 协议。

主要的改进点在于引入了**拥塞控制机制**（慢开始与拥塞避免），并将发送窗口的数据结构从静态数组重构为**动态链表**。特别针对累积确认机制下的窗口增长逻辑进行了修正，解决了“每收到一个 ACK 简单加 1”的逻辑错误，并严格遵守了“超时仅重传队首”的标准 Tahoe 行为。

---

## 2. 核心架构重构

### 2.1 动态窗口数据结构
为了适应 TCP Tahoe 中 `cwnd`（拥塞窗口）的动态变化，废弃了原有的静态数组实现，改用 Java 的 `LinkedList`。

*   **旧实现**：`WindowElem[] window` (静态大小)
*   **新实现**：`private LinkedList<WindowElem> window`
*   **内存管理**：在收到 ACK 进行窗口滑动时，使用迭代器显式执行 `remove()` 操作，彻底清除已确认的包，防止内存泄漏。
*   **发送条件**：`isFull()` 的判断逻辑由固定 Size 改为 `window.size() >= (int)cwnd`。

### 2.2 拥塞控制变量
在 `SenderWindow` 类中增加了以下核心变量：
```java
private double cwnd = 1.0;         // 拥塞窗口，初始化为1，支持浮点运算
private int ssthresh = 16;         // 慢开始阈值，初始值设为较大值
private int dupAckCount = 0;       // 冗余ACK计数（用于快重传）
private int lastAckSeq = -1;       // 上一次ACK序号
```

---

## 3. 关键算法实现与逻辑修正

### 3.1 累积确认感知与窗口增长
针对学长报告中提到的“*每收到 1 个 ACK 并不意味着发出了 1 个包*”的问题，本实现引入了 **`ackedCount` (本次实际确认包数)** 的计算逻辑。

**逻辑流程**：

1.  收到 `ACK=n` 时，遍历发送窗口链表。
2.  移除所有序号 `seq <= n` 的包，并统计移除数量 `ackedCount`。
3.  **慢开始阶段 (`cwnd < ssthresh`)**：
    *   **修正前**：`cwnd++` (错误，未考虑累积确认)。
    *   **修正后**：`cwnd += ackedCount`。若一次 ACK 确认了 3 个包，窗口应增加 3，保证指数增长特性。
4.  **拥塞避免阶段 (`cwnd >= ssthresh`)**：
    *   **算法**：`cwnd += ackedCount * (1.0 / cwnd)`。这实现了每经过一个 RTT（即确认了当前窗口所有包），窗口大小增加 1 MSS 的线性增长特性。

### 3.2 超时重传策略
针对老师指出的常见错误“*超时重传了窗口内的所有包*”，本实现进行了严格限制。

**超时行为 (`onTimeout`)**：

1.  **阈值调整**：`ssthresh = Math.max(2, (int)cwnd / 2)`。
2.  **窗口重置**：`cwnd = 1.0`。
3.  **重传动作**：**仅重传窗口队首（Head）的一个数据包**。
    *   代码体现：`sender.udt_send(window.peekFirst().getPacket())`。
    *   原理：由于 `cwnd` 降为 1，网络容量被认为极低，因此只能发送 1 个包（即丢失的那个），等待其 ACK 后再进入慢开始。

### 3.3 快重传 (Fast Retransmit)
实现了基于 3 次冗余 ACK 的快重传机制：
*   若计算出的 `ackedCount == 0`，说明收到了重复 ACK。
*   `dupAckCount++`。
*   当 `dupAckCount == 3` 时：
    *   `ssthresh = Math.max(2, (int)cwnd / 2)`
    *   `cwnd = 1` (Tahoe 标准行为，不同于 Reno)
    *   立即重传队首包。

---

## 4. 核心代码片段展示

### 4.1 `ackPacket` 方法实现
```java
public void ackPacket(int ackSeq) {
    // 步骤1：清理窗口并统计累积确认数量
    int ackedCount = 0;
    Iterator<WindowElem> iter = window.iterator();
    while (iter.hasNext()) {
        WindowElem elem = iter.next();
        if (elem.getSeq() <= ackSeq) {
            iter.remove();  // 显式移除，防止内存泄漏
            ackedCount++;
        } else {
            break; // 链表有序，后续seq肯定更大
        }
    }

    // 步骤2：拥塞控制状态机
    if (ackedCount > 0) {
        // --- 有效确认 ---
        baseTimer.restart(); // 重启计时器
        dupAckCount = 0;
        
        // 核心修正：基于 ackedCount 更新窗口
        if (cwnd < ssthresh) {
            // [慢开始]: 指数增长
            cwnd += ackedCount; 
        } else {
            // [拥塞避免]: 线性增长 (每RTT +1)
            cwnd += ackedCount * (1.0 / cwnd);
        }
        
        fillWindow(); // 尝试发送新数据
    } else {
        // --- 冗余 ACK ---
        dupAckCount++;
        if (dupAckCount == 3) {
            // [快重传]
            System.out.println("!!! 快重传触发 !!!");
            ssthresh = Math.max(2, (int)cwnd / 2);
            cwnd = 1.0;
            // 立即重传队首
            if (!window.isEmpty()) {
                sender.udt_send(window.peekFirst().getPacket());
                baseTimer.restart();
            }
        }
    }
}
```

### 4.2 `onTimeout` 方法实现
```java
private void onTimeout() {
    System.out.println("!!! TCP Tahoe超时 - ssthresh降为" + ((int)cwnd/2) + ", cwnd重置为1 !!!");
    
    // 1. 调整阈值和窗口
    ssthresh = Math.max(2, (int)cwnd / 2);
    cwnd = 1.0;
    
    // 2. 仅重传窗口左沿的包 (老师要求的关键点)
    if (!window.isEmpty()) {
        TCP_PACKET packet = window.peekFirst().getPacket();
        sender.udt_send(packet);
        System.out.println("TCP重传队首 - seq=" + packet.getTcpH().getTh_seq());
    }
    
    // 3. 重启计时器
    baseTimer.restart();
}
```

---

## 5. 易错点合规性检查

| 检查项 (老师要求) | 本项目实现状态 | 说明                                                         |
| :---------------- | :------------: | :----------------------------------------------------------- |
| **数据结构**      |     ✅ 通过     | 使用 `LinkedList` 替代静态数组，支持动态扩容。               |
| **内存管理**      |     ✅ 通过     | 窗口滑动时使用 `Iterator.remove()` 彻底清除对象。            |
| **慢开始实现**    |     ✅ 通过     | `cwnd < ssthresh` 时，`cwnd += ackedCount`，实现指数增长。   |
| **拥塞避免实现**  |     ✅ 通过     | `cwnd >= ssthresh` 时，按 `1/cwnd` 比例增加，实现线性增长。  |
| **超时重传行为**  |     ✅ 通过     | 超时后 `cwnd=1`，且代码明确**只重传 `window.peekFirst()`**，未遍历重传整个窗口。 |
| **累积确认计算**  |     ✅ 通过     | 引入 `ackedCount` 变量，正确处理了一个 ACK 确认多个包时的窗口扩张。 |
| **单计时器**      |     ✅ 通过     | 沿用 GBN 架构，仅维护一个 `baseTimer`，在重传或收到 ACK 时重启。 |

---

## 6. 结论

本次实现成功将基础 TCP 协议升级为支持 **TCP Tahoe** 拥塞控制的协议。通过引入动态链表结构和严谨的状态机逻辑，不仅实现了标准的慢开始和拥塞避免算法，还特别修正了累积确认下的窗口计算问题，并严格遵守了关于超时重传行为的限制要求。代码逻辑清晰，符合高可靠性网络协议的设计标准。
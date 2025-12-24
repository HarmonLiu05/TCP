/***************************2.2: 重复ACK（不使用NACK）
**************************** Modified for RDT 2.2*/

package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Sender extends TCP_Sender_ADT {
	
	private TCP_PACKET tcpPack;	//待发送的TCP数据报
	private volatile int flag = 0;
	private int lastAckReceived = 0;  // 记录上一次收到的ACK号（用于检测重复ACK）
	
	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
	}
	
	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报；需要修改
	public void rdt_send(int dataIndex, int[] appData) {
		
		//生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序
		tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号：
		tcpS.setData(appData);
		tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);		
		//更新带有checksum的TCP 报文头		
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		
		//发送TCP数据报
		udt_send(tcpPack);
		flag = 0;
		
		//等待ACK报文
		//waitACK();
		while (flag==0);
	}
	
	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送；需要修改错误标志
	public void udt_send(TCP_PACKET stcpPack) {
		//设置错误控制标志
		// eflag=0: 无差错; eflag=1: 只出错; eflag=2: 只丢包; eflag=3: 只延迟
		// 为了测试RDT2.2，设置为1（只出错）
		tcpH.setTh_eflag((byte)1);		
		//System.out.println("to send: "+stcpPack.getTcpH().getTh_seq());				
		//发送数据报
		client.send(stcpPack);
	}
	
	@Override
	//需要修改
	public void waitACK() {
		//循环检查ackQueue
		//循环检查确认号对列中是否有新收到的ACK		
		if(!ackQueue.isEmpty()){
			int currentAck=ackQueue.poll();
			// System.out.println("CurrentAck: "+currentAck);
			if (currentAck == tcpPack.getTcpH().getTh_seq()){
				System.out.println("Clear: "+tcpPack.getTcpH().getTh_seq());
				flag = 1;
				//break;
			}else{
				System.out.println("Retransmit: "+tcpPack.getTcpH().getTh_seq());
				udt_send(tcpPack);
				flag = 0;
			}
		}
	}

	@Override
	//接收到ACK报文：RDT2.2 通过检测重复ACK来判断错误
	public void recv(TCP_PACKET recvPack) {
		int receivedAck = recvPack.getTcpH().getTh_ack();
		int currentSeq = tcpPack.getTcpH().getTh_seq();
			
		System.out.println("Receive ACK Number： " + receivedAck + " (Current seq: " + currentSeq + ")");
			
		//RDT 2.2 核心逻辑：检测重复ACK
		if (receivedAck == currentSeq) {
			// 情况1：收到正确ACK，确认号与当前发送序号匹配
			System.out.println("RDT2.2 - Correct ACK, Clear: " + currentSeq);
			lastAckReceived = receivedAck;  // 更新上次ACK
			flag = 1;  // 设置标志，允许发送下一包
		} else if (receivedAck == lastAckReceived && receivedAck != 0) {
			// 情况2：RDT 2.2 重复ACK → 说明当前包出错，需要重传
			System.out.println("RDT2.2 - Duplicate ACK detected (ACK=" + receivedAck + "), Retransmit: " + currentSeq);
			udt_send(tcpPack);  // 重传当前包
		} else if (receivedAck < currentSeq) {
			// 情况3：收到旧的ACK（可能是第一次收到的重复ACK）
			System.out.println("RDT2.2 - Old ACK (ACK=" + receivedAck + " < seq=" + currentSeq + "), Retransmit: " + currentSeq);
			lastAckReceived = receivedAck;  // 记录这个ACK，下次如果重复就能检测到
			udt_send(tcpPack);  // 重传
		} else {
			// 惄况4：其他错误情况
			System.out.println("RDT2.2 - Unexpected ACK, Retransmit: " + currentSeq);
			udt_send(tcpPack);
		}
		System.out.println();
	}
	
}

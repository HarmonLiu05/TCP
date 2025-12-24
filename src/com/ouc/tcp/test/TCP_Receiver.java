/***************************2.2: 重复ACK（不使用NACK）*****************/
/***** Modified for RDT 2.2 ******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Receiver extends TCP_Receiver_ADT {
	
	private TCP_PACKET ackPack;	//回复的ACK报文段
	int sequence=1;//用于记录当前待接收的包序号，注意包序号不完全是
	int lastCorrectSeq = 0; //记录上一个正确接收的包的序号（用于RDT2.2重复ACK）
		
	/*构造函数*/
	public TCP_Receiver() {
		super();	//调用超类构造函数
		super.initTCP_Receiver(this);	//初始化TCP接收端
	}

	@Override
	//接收到数据报：检查校验和，设置回复的ACK报文段
	public void rdt_recv(TCP_PACKET recvPack) {
		//检查校验码，生成ACK
		if(CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
			//数据包正确，生成ACK报文段（设置确认号）
			tcpH.setTh_ack(recvPack.getTcpH().getTh_seq());
			ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
			tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
			//设置错误控制标志为0（数据无错误）
			tcpH.setTh_eflag((byte)0);
			//回复ACK报文段
			reply(ackPack);
			
			//更新上一个正确接收的包序号
			lastCorrectSeq = recvPack.getTcpH().getTh_seq();
			
			//将接收到的正确有序的数据插入data队列，准备交付
			dataQueue.add(recvPack.getTcpS().getData());				
			sequence++;
			
			System.out.println("RDT2.2 - ACK for seq: " + recvPack.getTcpH().getTh_seq());
		}else{
			//RDT 2.2：数据包损坏，发送上一个正确接收的包的ACK（重复ACK）
			System.out.println("Recieve Computed: "+CheckSum.computeChkSum(recvPack));
			System.out.println("Recieved Packet: "+recvPack.getTcpH().getTh_sum());
			System.out.println("Problem: Packet Number: "+recvPack.getTcpH().getTh_seq()+" + InnerSeq: "+sequence);
			
			//RDT 2.2核心：不使用NACK，而是重发上一个正确ACK
			tcpH.setTh_ack(lastCorrectSeq);  // 发送上一个正确的ACK（重复ACK）
			ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
			tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
			//ACK包也设置为0（这是正常的ACK，只是重复的）
			tcpH.setTh_eflag((byte)0);
			//回复重复ACK
			reply(ackPack);
			
			System.out.println("RDT2.2 - Duplicate ACK for seq: " + lastCorrectSeq);
		}
		
		System.out.println();
		
		
		//交付数据（每20组数据交付一次）
		if(dataQueue.size() == 20) 
			deliver_data();	
	}

	@Override
	//交付数据（将数据写入文件）；不需要修改
	public void deliver_data() {
		//检查dataQueue，将数据写入文件
		File fw = new File("recvData.txt");
		BufferedWriter writer;
		
		try {
			writer = new BufferedWriter(new FileWriter(fw, true));
			
			//循环检查data队列中是否有新交付数据
			while(!dataQueue.isEmpty()) {
				int[] data = dataQueue.poll();
				
				//将数据写入文件
				for(int i = 0; i < data.length; i++) {
					writer.write(data[i] + "\n");
				}
				
				writer.flush();		//清空输出缓存
			}
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	//回复ACK报文段
	public void reply(TCP_PACKET replyPack) {
		//直接发送数据报，保留之前设置的eflag值
		client.send(replyPack);
	}
	
}

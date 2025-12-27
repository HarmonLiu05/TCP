查看日志文件:

![image-20251224155600537](C:\Users\liuhe\AppData\Roaming\Typora\typora-user-images\image-20251224155600537.png)

图片说明seq为5401的数据包loss了 所以经过了3s进行了重传可以看到时间从7s变到了10s

说明确实是在倒计时结束之后才进行的重传数据包5401

![image-20251224155814808](C:\Users\liuhe\AppData\Roaming\Typora\typora-user-images\image-20251224155814808.png)

由图片可知:当Sender接收到对于seq为5301的ack之后，Sender应该立即发送了seq为5401的包，但由于包丢失了，而Receiver直到3s后，5401包重发后才接收到数据包。从日志中的时间戳也可以看出大致经过大约3s，Receiver才接收到Sender发送的5401数据包

我所说的5401数据包均为seq为5401的数据包。


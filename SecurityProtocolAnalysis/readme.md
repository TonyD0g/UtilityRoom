# 基于RSA和Socket的简易单向通讯

学校开设的《安全协议分析》结课设计

东拼西凑出来的代码,无技术含量

---

### **特性：**

- socket中加入数据库校验
- 有登录次数限制和预编译,防暴力破解(防止弱口令爆破,从client.ip入手进行限制)和SQL注入

- 通讯过程使用RSA算法,使wireshark抓包后无法分析通讯内容

- 添加用户/删除用户(涉及数据库操作)
- JavaFx图形界面
- Server端接受数据之后将数据发送给Client端,Client显示接收到Server的数据。 (全部采用rsa实现) 
- 完整性校验方法。	(发的和收的使用md5校验) 
- 实现ClientA与ClientB通过Server进行安全聊天功能。 (使用server进行转发,多线程同时接受两个socket)

## 简写说明

交换机 = LSW

路由 = AR



## 开启路由器

```md
system-view
[Huawei]sysname r1 //修改设备名称
```

## 显示路由信息

```md
dis ip routing-table
```

## AR配置IP

```md
sys
sys AR1
int g0/0/0
ip add ip 24
```

## 配置Loopback

```md
#以AR1为例
int loo0
ip add 1.1.1.1 32
```



## 查看配置命令
```md
display current-configuration //查看当前生效的配置
display this //查看当前位置的配置
```



## 配置DHCP

```md
[r3]dhcp enable //打开DHCP服务
[r3]ip pool qw //创建名为qw的地址池塘

Info: It's successful to create an IP address pool. //系统提示

[r3-ip-pool-qw]network 192.168.1.1 mask 255.255.255.2 40 //定义池塘地址范围

[r3-ip-pool-qw]gateway-list 192.168.1.2 //定义网关
[r3-ip-pool-qw]dns-list 114.114.114.114 //定义DNS服务

[r3-GigabitEthernet0/0/2]dhcp select global //接口处打开调用
```



## 静态路由
```md
[r1]ip route-static 0.0.0.0 0 172.16.2.2 //添加缺省路由
[r1]ip route-static 10.1.0.0 22 NULL 0 //配置空接口路由
[r1]display ip routing-table //查看路由器的路由表
[r1]display ip routing-table protocol ospf //查看具体协议的路由表
[r1]interface LoopBack 0 /// 创建环回接口
```

## 交换机常用命令-交换机端口安全
**1.设置连接数量**

```md
[S1]interface Ethernet 0/0/5 #选中端口
[S1-Ethernet0/0/5]port-security enable #激活安全设置
[S1-Ethernet0/0/5]port-security protect-action shutdown #如果有机器违反规则，封掉端口
[S1-Ethernet0/0/5]port-security max-mac-num 1 #最多允许一台主机连接
```

**2.指定交换机接口连接的MAC地址**

```md
[S1]interface Ethernet 0/0/3 #选中端口
[S1-Ethernet0/0/3]port-security enable #开启功能
[S1-Ethernet0/0/3]port-security protect-action shutdown #设置违反后处理方法
[S1-Ethernet0/0/3]port-security mac-address sticky # 进入MAC地址设置
[S1-Ethernet0/0/3]port-security mac-address sticky 5489-981F-22FB vlan 1 #设置允许连接的MAC地址
[S1]display mac-address vlan 1 # 查看设置好的内容
```

**3.清除交换机接口的全部配置**

```md
[S1]clear configuration interface Ethernet 0/0/3
```



## 常用的简单命令
```md
system-view ——用用户模式切换到系统配置模式
display this ——显示当前位置的设置信息，很方便了解系统设置
display 端口 ——显示端口的相关信息
shutdown ——当进入了一个端口后，使用shutdown可以关闭该端口
undo 命令 ——执行与命令相反的操作，如undo shutdown是开启该端口
quit ——退出当前状态
sysname 设备名 ——更改设备的名称
interface eth-trunk 1 ——创建汇聚端口1（若已创建则是进入）
interface GigaBitEthernet 0/0/1 ——进入千兆以太网端口1的设置状态
bpdu enable ——允许发送bpdu信息
ip address 192.168.0.10 24 ——设置ip地址，24代表24位网络号
vlan 10 ——进入vlan 10的配置状态
eNSP命令的格式都很有规律的，动手操作几回掌握其要点，根据手册就很容易掌握大部分命令的使用方法了。
```

## 配置OSPF

(数据都是需要自己修改)

**1.基本ospf**

(`1.1.1.1 ` 为 自定义名称,如AR1可以叫`1.1.1.1`,AR2可以叫`2.2.2.2`)

(area `0 ` 中, 0 为区域代号 )

```md
ospf 1 router-id 1.1.1.1 
area 0.0.0.0 
network 10.1.123.0 0.0.0.255 
```



**2.配置虚连接(虚连接需要两个设备相互配置)**

```md
如
AR1:
ospf 1  
area 2 
vlink-peer 5.5.5.5

AR5:
ospf 1  
area 2 
vlink-peer 1.1.1.1
```

**3.配置area 1为nssa区域**

```md
ospf 1  
area 0.0.0.1 
nssa no-summary
```

**4.配置`xx处`的安全性**

(密码为 `huawei`)

```md
int g0/0/1
ospf authentication-mode hmac-md5 1 cipher huawei
```

**5.引入外部路由**

配置rip协议

```md
rip 1 z

version 2

network 110.0.0.0
import-route ospf 1

ospf 1  
import-route rip 1 type 1
```

## 配置ACL

```md
AR1:
acl 2000

rule permit source 192.168.1.0 0.0.0.255

q

int g0/0/2

nat outbound 2000

--------------------------


```

## NAT Server配置

```md
int g0/0/0

ip add ip

int s0/0/0

ip add ip 24

nat server protocol tcp global  ip www inside ip 80
如：
nat server protocol tcp global 202.112.1.3 80 inside 192.168.3.10 80
```

## 网络防火墙

```md
1.两个安全区域是通过安全级别区分的，所以不存在两个完全相同安全级别的安全区域

2.不允许同一物理接口分属于两个不同的安全区域

3.不同接口可以属于同一安全区域

4.
Local区域的安全级别是100，Trust区域的安全级别是85，DMZ区域的安全级别是50，Untrust区域的安全级别是5

5.防火墙的缺省包过滤策略:
获取报文中的IP首部以及 TCP/UDP首部，获取发送源的 IP地址和端口号，以及目的地的IP地址和端口号，并将这些信息作为过滤条件

6.分为包过滤防火墙，代理防火墙，状态检测防火墙

7.server-map表是一种映射关系,当数据连接匹配了动态Server-map表项时，不需要在查找 包过滤策略 ，保证了某些特殊应用的正常转发
```

实验过程:

```md
1.配置PC的地址及网关
2.配置防火墙的端口地址
3.将端口添加到相应的安全区域
firewall zone trust 
add int g1/0/1

安全策略配置:
security-policy
rule name t_u
source-zone trust	//trust区域
destination-zone untrust	//目的区域为 untrust
source-address 10.1.1.0 24	// 源地址
action permit				// 设置为允许通过的状态

显示会话表详细信息：
display Firewall session table verbose


```

## NAT与防火墙

```md
1.创建nat策略，进入策略视图
destination-address


配置源nat策略

security-policy
rule name police_nat
source-zone trust	//trust区域
destination-zone untrust	//目的区域为 untrust
source-address 10.1.1.0 24	// 源地址
action source-nat address-group addg1		//addg1为地址池


配置黑洞路由 
ip route-static 1.1.1.10 255.255.255.255 NULL0

```



## 网络安全隔离技术	——防火墙双机热备技术

```md
1.想用vrrp协议，需要二层交换路由器

2.vrrp用于防火墙多区域备份

3.HRP:用来将主防火墙关键配置和连接状态等数据向备防火墙同步
	备份的方式:	自动备份,手工批量备份,快速备份
	备份通道(心跳口):两条设备直连
	心跳口不能为二层以太网接口
	当心跳口通过中间设备相连时，需要配置remote参数来指定对端IP地址

4.
```



**实验**

```md
配置vrrp:
vrrp vrid 

指定心跳口:

启用HRP备份功能:


```





### 小技巧：

1.保存命令：进入各个设备，然后必须是尖括号状态，分别输入save



2.设备加端口:

先关闭AR设备，再右键，选择设置选项,将下面的"4GEW-T"拖到右上角，然后启动



3.LSW可以视为不存在, AR1连接到LSW时，ip add 网关ip 24



4. 防火墙默认用户为:admin 默认密码为:Admin@123, 改密码为Huawei@123



# 课上杂记(每节课清空一次)

```md
简写说明:

int 2 是进入2号口

add 是 ip add

fi 是 firewall

tr 是 trust

untr 是untrust

sou-zon 是 source-zone

des-zon 是 destination-zone

na 是 name

sec 是 section

ad-gr 是 address-group

24 是 255.255.255.255
```



```md
AR1:
int g0/0/0
ipadd 10.1.11.1 2

int g0/0/1
ip add 10.1.12.1 24

int loo0
ipadd 1.1.1.1 32



FW1:
int g100
ip add 10.1.11.2 24
int g101
ipadd 192.168.10.1 24
int g102
ipadd 12.1.1.1 24

fir zon tr
add int g101
q

fir zon untr
add int g100
q

fir zon dmz
add int g102
q




FW2:
int g100
ip add 10.1.12.2 24
int g101
ip add 192.168.10.2 24
int g102

AR1:
sys
ospf 1 rou 1.1.1.1
area 0
network 0.0.0.0 0.0.0.0

FW1:
ospf 1 rou 2.2.2.2
area 0
network 10.1.11.0 0.0.0.255
network 192.168.10.0 0.0.255
q

// 配置安全策略
sec-po
rule name permit_ospf
// local -> untrust	untrust->local
sour-zon local untrust
dest-zon un
service protocal 89
action permit

dis ospf peer brief

FW2:
ospf 1 rou 3.3.3.3
area 0
network 10.1.12.0 0.0.255
network 192.168.10.0 0.0.0.255
q

sec-po
rule name permit_ospf
// local -> untrust	untrust->local
sour-zon local untrust
dest-zon un
service protocal 89
action permit

FW1:
int g101
vrrp vrid 1 vir-ip 192.168.10.254 active

FW2:
int g101
vrrp vrid 1 vi-ip 192.168.10.254 st
q

hrp int g102 remote 
enable


LSW1:

PC1:
192.168.10.10
24
192.168.10.254

FW1:
HRP INT G102 re 12.1.1.2



PC2:


```


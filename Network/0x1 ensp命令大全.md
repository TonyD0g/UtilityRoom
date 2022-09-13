## 一、开启路由器
system-view
[Huawei]sysname r1 //修改设备名称

## 二、查看配置命令
[r1]display current-configuration //查看当前生效的配置
[r1]display this //查看当前位置的配置

## 三、配置DHCP
[r3]dhcp enable //打开DHCP服务
[r3]ip pool qw //创建名为qw的地址池塘
Info: It's successful to create an IP address pool. //系统提示
[r3-ip-pool-qw]network 192.168.1.1 mask 255.255.255.240 //定义池塘地址范围
[r3-ip-pool-qw]gateway-list 192.168.1.2 //定义网关
[r3-ip-pool-qw]dns-list 114.114.114.114 //定义DNS服务
[r3-GigabitEthernet0/0/2]dhcp select global //接口处打开调用

## 四、静态路由
[r1]ip route-static 0.0.0.0 0 172.16.2.2 //添加缺省路由
[r1]ip route-static 10.1.0.0 22 NULL 0 //配置空接口路由
[r1]display ip routing-table //查看路由器的路由表
[r1]display ip routing-table protocol ospf //查看具体协议的路由表
[r1]interface LoopBack 0 /// 创建环回接口

## 五、交换机常用命令-交换机端口安全
### 1.设置连接数量
[S1]interface Ethernet 0/0/5 #选中端口
[S1-Ethernet0/0/5]port-security enable #激活安全设置
[S1-Ethernet0/0/5]port-security protect-action shutdown #如果有机器违反规则，封掉端口
[S1-Ethernet0/0/5]port-security max-mac-num 1 #最多允许一台主机连接
### 2.指定交换机接口连接的MAC地址
[S1]interface Ethernet 0/0/3 #选中端口
[S1-Ethernet0/0/3]port-security enable #开启功能
[S1-Ethernet0/0/3]port-security protect-action shutdown #设置违反后处理方法
[S1-Ethernet0/0/3]port-security mac-address sticky # 进入MAC地址设置
[S1-Ethernet0/0/3]port-security mac-address sticky 5489-981F-22FB vlan 1 #设置允许连接的MAC地址
[S1]display mac-address vlan 1 # 查看设置好的内容
### 3.清除交换机接口的全部配置
[S1]clear configuration interface Ethernet 0/0/3

## 4.常用的简单命令
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



小技巧：

保存命令：进入各个设备，然后必须是尖括号状态，分别输入save




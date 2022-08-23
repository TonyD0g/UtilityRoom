package Input;

import java.util.Scanner;

public class InputMain {
    // 每次输入数据的最大次数
    public static int inputNum = 3 ,  i = 0 ,x = 0, flag  = 0;;
    public static Scanner input = new Scanner(System.in);

    // 输入数据主函数
    public static String[]  input_main(String text1,int wantNum) {
        if(wantNum == 0){
            wantNum = inputNum;
        }
        String[] data1 = new String[wantNum];
        String middle;


        System.out.println(text1+"\t输入 0 表示停止输入");

        for (i= 0;i<wantNum;i++){
            middle = input.nextLine();

            if (middle == "") {
                System.out.println("[-] 请重新输入数据：");
                data1[i] = middle;
                flag = 1;
            }else if(middle.equals("0")){
                break;
            }
            if(flag == 0){
                data1[i] = middle;
            }
            flag = 0;
        }

        return data1;
    }

    // 输入你的身份 （蓝、 红、无）
    public static String  input_idCard(){
        System.out.println("[+] 请输入你的身份\n1: 蓝队\t2: 红队\t3: 平民");
        x = input.nextInt();
        switch (x){
            case 1:
                return "blue";
            case 2:
                return "red";
            case 3:
                return "simple";
        }
        return "";
    }
}

/*
 谁是卧底分析器
 */

import Handle.HandleMain;
import Input.InputMain;
import UACArray.FristAndLastArray;

import java.util.Arrays;
import java.util.Objects;
import java.util.Scanner;

public class Main {
    // 每次输入数据的最大次数
    public static int inputNum = 3;
    public static Scanner input = new Scanner(System.in);
    public static int flag = 0;

    // 待更新：保存可疑的数组
    public static void main(String args[]) {
        String[] middle = new String[18];
        String[] yourFriend = new String[inputNum];
        String[] maybe_GJ = new String[inputNum];
        int[] hasDie = new int[0];
        FristAndLastArray fristAndLastArray = new FristAndLastArray();

        System.out.println("---------------欢迎使用谁是卧底分析器，分析结果仅供参考 版本0.3---------------\n");

        // 输入你的身份
        String idCard = InputMain.input_idCard();
        if (idCard.equals("simple") != true) {
            // 输入你的队友
            yourFriend = InputMain.input_main("输入你的队友: ", 4);
        }

        // 根据身份选择对应的逻辑
        switch (idCard) {
            case "blue":
                fristAndLastArray = HandleMain.handle_blue();
                break;
            case "red":
                fristAndLastArray = HandleMain.handle_red();
                break;
            case "simple":
                fristAndLastArray = HandleMain.handle_simple();
                break;
        }
        middle = fristAndLastArray.yourCare;
        flag = 1;

        // 三层逻辑
        /*
        逻辑1：    我是蓝队
        逻辑2：    我是红队
        逻辑3：    没有身份
         */
        int i = 0;
        while (true) {
            // 根据身份选择对应的逻辑
            if (flag == 0) {
                switch (idCard) {
                    case "blue":
                        fristAndLastArray = HandleMain.handle_blue();
                        break;
                    case "red":
                        fristAndLastArray = HandleMain.handle_red();
                        break;
                    case "simple":
                        fristAndLastArray = HandleMain.handle_simple();
                        break;
                }
            }
//
            flag = 0;
            // 插入一些算法

            // 排除算法,结果为 尚不可知的人
            int[] excludeAarry = HandleMain.exclude(fristAndLastArray, yourFriend, hasDie, middle);

            i++;
            // 前置位可疑
            try {
                if (fristAndLastArray == null) {
                    System.out.println("[+] 暂无怀疑的，建议使用前一轮的数据");
                } else if (fristAndLastArray.lastArray[0] == null && fristAndLastArray.fristArray[0] != null) {
                    System.out.println("[-] 后置位为空");
                    System.out.println("1. 较为可疑的：" + Arrays.toString(fristAndLastArray.fristArray)+" ,建议查一下");
                } else if (fristAndLastArray.fristArray[0] == null && fristAndLastArray.lastArray[0] != null) {
                    System.out.println("[-] 前置位为空");
                    System.out.println("2. 较为可疑的：" + Arrays.toString(fristAndLastArray.lastArray)+" ,建议查一下");
                } else if (Objects.equals(fristAndLastArray.choice_Array, "fristArray")) {
                    System.out.println("---------------\n[+] 第 " + i + " 轮 辅助分析结果：\n我是" + idCard + "\n");
                    System.out.println("1. 较为可疑的：" + Arrays.toString(fristAndLastArray.fristArray)+" ,建议查一下");
                    System.out.println("\n2. 次可疑的：" + Arrays.toString(fristAndLastArray.lastArray)+" ,建议查一下");

                } else {
                    System.out.println("---------------\n[+] 第 " + i + " 轮 辅助分析结果：\n我是" + idCard + "\n");
                    System.out.println("1. 较为可疑的：" + Arrays.toString(fristAndLastArray.lastArray)+" ,建议查一下");
                    System.out.println("\n2. 次可疑的：" + Arrays.toString(fristAndLastArray.fristArray)+" ,建议查一下");
                }
                System.out.println("\n3. 尚不可知的：" + Arrays.toString(excludeAarry) + "\n---------------");

            } catch (Exception e) {
                System.out.println("[-] 输出出现错误！请排查");
            }

            hasDie = HandleMain.excludeDie();

        }


    }
}

package MHYS;

import java.util.Scanner;

// 梅花易数
public class PlumBlossomeI {
    public static void main(String args[]) {
        char[] tianGanArray = {'甲', '乙', '丙', '丁', '戊', '己', '庚', '辛', '壬', '癸'};
        char[] diZhiArray = {'子', '丑', '寅', '卯', '辰', '巳', '午', '未', '申', '酉', '戍', '亥'};
        char[] alphabetArray = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'};
        int outcome = 0;

        Scanner input = new Scanner(System.in);

        System.out.println("请输入天干地支,可不输入");
        String inputString = input.nextLine();
        outcome = outcome + GetNum(tianGanArray, inputString,10);

        System.out.println("请输入天干地支,可不输入");
        inputString = input.nextLine();
        outcome = outcome + GetNum(diZhiArray, inputString,12);

        System.out.println("请输入拼音");
        inputString = input.nextLine();
        outcome = outcome + GetNum(alphabetArray, inputString,26);
        System.out.println("outcome:" + outcome);


    }

    // 输入数据获取数字
    public static int GetNum(char[] Array, String inputString,int number) {
        int num = 0;

        for (int i = 0; i < inputString.length(); i++) {
            for (int j = 0; j < number; j++) {
                if (inputString.charAt(i) == Array[j]) {
                    num = num + j + 1;
                }
            }

        }
        return num;
    }
}

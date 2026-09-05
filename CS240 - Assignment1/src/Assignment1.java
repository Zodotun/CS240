import javax.imageio.ImageIO;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.InputMismatchException;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.io.*;
import java.util.List;

import java.util.*;

public class Assignment1 {
    /*
    Assignment 1: Converter and Pixel System

    1. Build an ASCII-to-decimal converter.
    2. Build a number-base converter supporting binary, decimal, octal, and hexadecimal.
    3. Write a program that reads an image and prints its pixel values. bit values to hexadecimal
    4. Write a program that consumes pixel values and creates an image.
    5. Test boundary cases, including zero, the largest supported unsigned value, and at least one negative two's-complement value.


     */


    public static void prob1(String t) {
        for (int i = 0; i < t.length(); i++) {
            System.out.print( (int) t.charAt(i) + " ");
        }
    }

    public static void prob2() {
       Scanner input = new Scanner(System.in);
       /*
            Long.parseLong(given number, base number); Number to decimal

        */

        System.out.println("Choose from which base to translate into Decimal:\n\n 1. Binary\n2. Decimal\n3. Octal\n4. Hexadecimal");
        try {
            int option = input.nextInt(); // To choose a base

            System.out.println("Input a number to translate: ");
            long translateNum = input.nextLong();

            switch(option) {
                case 1 -> {
                    System.out.println(Long.toBinaryString(translateNum));
                }
                case 2 -> System.out.println(translateNum);

                case 3 -> System.out.println(Long.toOctalString(translateNum));

                case 4 -> System.out.println(Long.toHexString(translateNum));
                default -> {
                    return;
                }
            }

        } catch(NumberFormatException e) {
            System.out.println(e);
        }
    }


    /*
    public static void prob3() {
        // 3. Write a program that reads an image and prints its pixel values. bit values to hexadecimal
        BufferedImage image;

        try {
            File given = new File("");
            image = ImageIO.read(given);
            PrintWriter output = new PrintWriter("output.txt");
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    Color color = new Color(image.getRGB(x,y), true);
                    int r = color.getRed();
                    int g = color.getGreen();
                    int b = color.getBlue();
                    String s1 = "(" + r + ", " + g + ", " + b + ")";
                    output.print(s1);
                }
            }
            output.close();
        }
        catch (IOException e) {
            System.out.println("Invalid bruh");
        }

        System.out.println();
    } */

    /*
    Problem 5:

     5. Test boundary cases, including zero, the largest supported unsigned value, and at least one negative two's-complement value.

    Unsigned / Signed = (+) Numbers
    Signed = (-) Numbers

    A complement of an N-digit number is another number that yields a sum of 100...0 with N 0's (4 bit system)
    Signed int
    [-2.1B, 2.1B]
    Unsigned integer
    [0, 4.2B]

    Integer.toUnsignedString();
    Integer.parseUnsignedInt();
     */

    public static void prob5(long num) {
        /*
            Let boundary cases be 12, 0, 4294967295,

            And sloppy coding practices because im lowkey tired
         */
        // long x = 4294967295;

        long test1 = 0L;
        long test2 = 4294967295L;
        long test3 = -31L;


        // Test 0 variable
        List<Integer> toChange = binaryLazy(num);
        String c = ""; // To print out the 32-bit binary
        for (int i : toChange)
            c = c.concat(String.valueOf(i));
        System.out.println("\n\n"+c);

        String d = ""; // To flip the 0s and 1s

        d = flipDaSwitch(c, d);

        System.out.println(d);


        // Test 1 variable
        c = "";
        toChange = binaryLazy(test1);


        for (int i : toChange)
            c = c.concat(String.valueOf(i));
        System.out.println("\n"+c);

        d = "";
        d = flipDaSwitch(c, d);
        System.out.println(d);


        // Test 2 variable
        c = "";
        d = "";
        toChange = binaryLazy(test2);

        for (int i : toChange)
            c = c.concat(String.valueOf(i));

        d = flipDaSwitch(c, d);
        System.out.println("\n" + c + "\n" + d);

        // Test 3 variable
        c = "";
        d = "";
        toChange = binaryLazy(test3);

        for (int i : toChange)
            c = c.concat(String.valueOf(i));

        d = flipDaSwitch(c, d);
        System.out.println("\n" + c + "\n" + d);

    }

    private static List<Integer> binaryLazy(long num) {
        // Sloppy coding practices

        List<Integer> thing = new ArrayList<>();
        while (num != 0) {
            if (num % 2 == 0)
                thing.addFirst(0);
            else
                thing.addFirst(1);
            num /= 2;
        }

        if (thing.size() < 32) { // Just make it 32 bits bruh idc
            for (int i = thing.size(); i < 32; i++)
                thing.addFirst(0);
        }


        return thing;
    }

    private static String flipDaSwitch(String c, String d) {
        // String c -> The number that is translated into binary
        // String d -> The number translated into negative
        for (int i = 0; i < c.length(); i++) {
            if (c.charAt(i) == '1')
                d = d.concat("0");
            else if (c.charAt(i) == '0')
                d = d.concat("1");
        }

        if (d.charAt(c.length() - 1) == '0') {
            d = d.substring(0, 31) + "1";
        }
        return d; // We return the variable d for outputting reasons
    }

    public static void main() {
        String t = "thing";
        prob1(t);
        prob2();
        prob5(12);
    }

}

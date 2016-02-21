package ru.spbau.mit;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class SecondPartTasksTest {

    @Test
    public void testFindQuotes() {
        List<String> firstAnswer = Arrays.asList(
                "meow-meow",
                "mur-mur-meow",
                "meow meow meow",
                "    11meow22"
        );

        List<String> secondAnswer = Arrays.asList(
                " second string in a text for a first test",
                "  thirdstringinatextforafirsttest",
                "    string"
        );

        List<String> paths = Arrays.asList(
                "src/test/forTests/FindQuotesTest1.txt",
                "src/test/forTests/FindQuotesTest2.txt"
        );

        assertEquals(firstAnswer, SecondPartTasks.findQuotes(paths, "meow"));
        assertEquals(secondAnswer, SecondPartTasks.findQuotes(paths, "string"));
        assertEquals(Collections.emptyList(), SecondPartTasks.findQuotes(paths, "meow-meow-test"));
    }


    @Test
    public void testPiDividedBy4() {
        assertEquals(Math.PI / 4, SecondPartTasks.piDividedBy4(), 1e-3);
    }

    @Test
    public void testFindPrinter() {
        Map<String, List<String>> compositions = new HashMap<>();
        assertEquals(null, SecondPartTasks.findPrinter(compositions));

        compositions.put("Susan", Arrays.asList("meow", "hello, world!", "beautiful day", "arrr"));
        compositions.put("Martie", Arrays.asList("aaa", "bbbbbb", "g"));
        compositions.put("Emily", Arrays.asList("the biggest string", "c", "", "", "meow-meow-meow"));
        assertEquals("Susan", SecondPartTasks.findPrinter(compositions));

        compositions.remove("Susan");
        assertEquals("Emily", SecondPartTasks.findPrinter(compositions));

        compositions.put("John", Collections.singletonList("12345678910111213141516171819202122"));
        assertEquals("John", SecondPartTasks.findPrinter(compositions));
    }

    @Test
    public void testCalculateGlobalOrder() {
        String first = "dresses";
        String second = "cats";
        String third = "tea";

        assertEquals(new HashMap<String, Integer>(),
                SecondPartTasks.calculateGlobalOrder(Collections.emptyList())
        );

        HashMap<String, Integer> firstOrder = new HashMap<>();
        firstOrder.put(first, 150);
        firstOrder.put(second, 40);
        firstOrder.put(third, 1);

        HashMap<String, Integer> secondOrder = new HashMap<>();
        secondOrder.put(first, 34);
        secondOrder.put(third, 12);

        HashMap<String, Integer> thirdOrder = new HashMap<>();
        thirdOrder.put(first, 2);
        thirdOrder.put(second, 5);

        HashMap<String, Integer> totalOrder = new HashMap<>();
        totalOrder.put(first, 150 + 34 + 2);
        totalOrder.put(second, 40 + 5);
        totalOrder.put(third, 1 + 12);

        assertEquals(secondOrder,
                SecondPartTasks.calculateGlobalOrder(Collections.singletonList(secondOrder))
        );

        assertEquals(totalOrder,
                SecondPartTasks.calculateGlobalOrder(Arrays.asList(firstOrder, secondOrder, thirdOrder))
        );


    }
}
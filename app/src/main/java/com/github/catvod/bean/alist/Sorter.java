package com.github.catvod.bean.alist;

import java.util.*;

public class Sorter {

    private static final NaturalSorter nameSorter = new NaturalSorter();

    /**
     * 针对 Item 对象的排序
     */
    public static void sort(String type, String order, List<Item> items) {
        Collections.sort(items, new ItemComparator(type, order));
    }

    /**
     * 针对 String 的排序（通常只按名称自然排序）
     */
    public static void sort(String order, List<String> items) {
        Collections.sort(items, new StringComparator(order));
    }

    /**
     * Item 比较器
     */
    static class ItemComparator implements Comparator<Item> {
        private final String type;
        private final boolean asc;

        public ItemComparator(String type, String order) {
            this.type = type;
            this.asc = "asc".equals(order);
        }

        @Override
        public int compare(Item o1, Item o2) {
            int res = 0;
            switch (type) {
                case "name":
                    res = nameSorter.compare(o1.getName(), o2.getName());
                    break;
                case "size":
                    res = Long.compare(o1.getSize(), o2.getSize());
                    break;
                case "date":
                    res = o1.getDate().compareTo(o2.getDate());
                    break;
                default:
                    return 0;
            }
            return asc ? res : -res;
        }
    }

    /**
     * String 比较器
     */
    static class StringComparator implements Comparator<String> {
        private final boolean asc;

        public StringComparator(String order) {
            this.asc = "asc".equals(order);
        }

        @Override
        public int compare(String o1, String o2) {
            int res = nameSorter.compare(o1, o2);
            return asc ? res : -res;
        }
    }

    /**
     * 自然排序核心逻辑
     */
    static class NaturalSorter implements Comparator<String> {
        @Override
        public int compare(String o1, String o2) {
            if (o1 == null || o2 == null) return 0;
            String s1 = o1.replaceAll("\\s+", " ").replaceAll("\\.+", ".");
            String s2 = o2.replaceAll("\\s+", " ").replaceAll("\\.+", ".");
            int index1 = 0;
            int index2 = 0;
            while (true) {
                String data1 = nextSlice(s1, index1);
                String data2 = nextSlice(s2, index2);

                if (data1 == null && data2 == null) return 0;
                if (data1 == null) return -1;
                if (data2 == null) return 1;

                index1 += data1.length();
                index2 += data2.length();

                int result;
                if (isDigit(data1) && isDigit(data2)) {
                    // 使用 try-catch 防止超长数字导致 Long.valueOf 溢出
                    try {
                        result = Long.compare(Long.parseLong(data1), Long.parseLong(data2));
                    } catch (NumberFormatException e) {
                        result = data1.compareTo(data2);
                    }
                    if (result == 0) {
                        result = -Integer.compare(data1.length(), data2.length());
                    }
                } else {
                    result = data1.compareToIgnoreCase(data2);
                }

                if (result != 0) return result;
            }
        }

        private static boolean isDigit(String str) {
            char ch = str.charAt(0);
            return ch >= '0' && ch <= '9';
        }

        static String nextSlice(String str, int index) {
            int length = str.length();
            if (index >= length) return null;

            char ch = str.charAt(index);
            if (ch == '.' || ch == ' ') {
                return str.substring(index, index + 1);
            } else if (ch >= '0' && ch <= '9') {
                return str.substring(index, nextNumberBound(str, index + 1));
            } else {
                return str.substring(index, nextOtherBound(str, index + 1));
            }
        }

        private static int nextNumberBound(String str, int index) {
            int length = str.length();
            while (index < length) {
                char ch = str.charAt(index);
                if (ch < '0' || ch > '9') break;
                index++;
            }
            return index;
        }

        private static int nextOtherBound(String str, int index) {
            int length = str.length();
            while (index < length) {
                char ch = str.charAt(index);
                if (ch == '.' || ch == ' ' || (ch >= '0' && ch <= '9')) break;
                index++;
            }
            return index;
        }
    }
}
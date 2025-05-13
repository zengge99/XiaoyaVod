package com.github.catvod.bean.alist;

import java.util.*;
import java.util.regex.*;

public class Sorter implements Comparator<Item> {

    private final String type;
    private final String order;

    public static void sort(String type, String order, List<Item> items) {
        Collections.sort(items, new Sorter(type, order));
    }

    public Sorter(String type, String order) {
        this.type = type;
        this.order = order;
    }

    @Override
    public int compare(Item o1, Item o2) {
        boolean asc = order.equals("asc");
        switch (type) {
            case "name":
                return asc ? o1.getName().compareTo(o2.getName()) : o2.getName().compareTo(o1.getName());
            case "size":
                return asc ? Long.compare(o1.getSize(), o2.getSize()) : Long.compare(o2.getSize(), o1.getSize());
            case "date":
                return asc ? o1.getDate().compareTo(o2.getDate()) : o2.getDate().compareTo(o1.getDate());
            default:
                return 0;
        }
    }

    class NaturalSort {
        private final Map<String, Object> options;

        public NaturalSort(Map<String, Object> options) {
            this.options = options != null ? options : new HashMap<>();
        }

        public int compare(Item a, Item b) {
            final int EQUAL = 0;
            final int GREATER = "desc".equals(options.get("order")) ? -1 : 1;
            final int SMALLER = -GREATER;

            // 直接使用Item的getName()方法获取名称
            String aValue = a.getName();
            String bValue = b.getName();

            Pattern re = Pattern.compile("(^-?[0-9]+(\\.[0-9]*)?[df]?e?[0-9]?$|^0x[0-9a-f]+$|[0-9]+)", Pattern.CASE_INSENSITIVE);
            Pattern sre = Pattern.compile("(^[ ]*|[ ]*$)");
            Pattern dre = Pattern.compile("(^([\\w ]+,?[\\w ]+)?[\\w ]+,?[\\w ]+\\d+:\\d+(:\\d+)?[\\w ]?|^\\d{1,4}[/-]\\d{1,4}[/-]\\d{1,4}|^\\w+, \\w+ \\d+, \\d{4})");
            Pattern hre = Pattern.compile("^0x[0-9a-f]+$", Pattern.CASE_INSENSITIVE);
            Pattern ore = Pattern.compile("^0");

            String x = normalize(aValue).replaceAll(sre.pattern(), "");
            String y = normalize(bValue).replaceAll(sre.pattern(), "");

            String[] xN = x.replaceAll(re.pattern(), "\0$1\0").replaceAll("\0$", "").replaceAll("^\0", "").split("\0");
            String[] yN = y.replaceAll(re.pattern(), "\0$1\0").replaceAll("\0$", "").replaceAll("^\0", "").split("\0");

            if (x.isEmpty() && y.isEmpty()) return EQUAL;
            if (x.isEmpty() && !y.isEmpty()) return GREATER;
            if (!x.isEmpty() && y.isEmpty()) return SMALLER;

            Integer xD = null;
            Integer yD = null;

            Matcher xHreMatcher = hre.matcher(x);
            Matcher yHreMatcher = hre.matcher(y);
            Matcher xDreMatcher = dre.matcher(x);
            Matcher yDreMatcher = dre.matcher(y);

            if (xHreMatcher.find()) {
                xD = Integer.parseInt(x.substring(xHreMatcher.start(), xHreMatcher.end()), 16);
            } else if (xN.length != 1 && xDreMatcher.find()) {
                try {
                    xD = (int) (new Date(x).getTime() / 1000);
                } catch (Exception e) {
                    xD = null;
                }
            }

            if (yHreMatcher.find()) {
                yD = Integer.parseInt(y.substring(yHreMatcher.start(), yHreMatcher.end()), 16);
            } else if (xD != null && yDreMatcher.find()) {
                try {
                    yD = (int) (new Date(y).getTime() / 1000);
                } catch (Exception e) {
                    yD = null;
                }
            } else {
                yD = null;
            }

            if (yD != null) {
                if (xD != null && xD < yD) return SMALLER;
                else if (xD != null && xD > yD) return GREATER;
            }

            for (int cLoc = 0, numS = Math.max(xN.length, yN.length); cLoc < numS; cLoc++) {
                String xNcL = cLoc < xN.length ? xN[cLoc] : "";
                String yNcL = cLoc < yN.length ? yN[cLoc] : "";

                Object oFxNcL;
                Object oFyNcL;

                if (!ore.matcher(xNcL).find()) {
                    try {
                        oFxNcL = Double.parseDouble(xNcL);
                    } catch (NumberFormatException e) {
                        oFxNcL = xNcL.isEmpty() ? 0 : xNcL;
                    }
                } else {
                    oFxNcL = xNcL.isEmpty() ? 0 : xNcL;
                }

                if (!ore.matcher(yNcL).find()) {
                    try {
                        oFyNcL = Double.parseDouble(yNcL);
                    } catch (NumberFormatException e) {
                        oFyNcL = yNcL.isEmpty() ? 0 : yNcL;
                    }
                } else {
                    oFyNcL = yNcL.isEmpty() ? 0 : yNcL;
                }

                if ((oFxNcL instanceof Double && Double.isNaN((Double) oFxNcL)) != 
                    (oFyNcL instanceof Double && Double.isNaN((Double) oFyNcL))) {
                    return (oFxNcL instanceof Double && Double.isNaN((Double) oFxNcL)) ? GREATER : SMALLER;
                } else if (oFxNcL.getClass() != oFyNcL.getClass()) {
                    oFxNcL = oFxNcL.toString();
                    oFyNcL = oFyNcL.toString();
                }

                if (oFxNcL instanceof Comparable && oFyNcL instanceof Comparable) {
                    @SuppressWarnings("unchecked")
                    int compareResult = ((Comparable<Object>) oFxNcL).compareTo(oFyNcL);
                    if (compareResult < 0) return SMALLER;
                    if (compareResult > 0) return GREATER;
                }
            }

            return EQUAL;
        }

        private String normalize(String value) {
            String string = String.valueOf(value);
            return Boolean.TRUE.equals(options.get("caseSensitive")) ? string : string.toLowerCase();
        }
    }
}

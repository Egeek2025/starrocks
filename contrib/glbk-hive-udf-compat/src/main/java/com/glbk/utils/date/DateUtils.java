package com.glbk.utils.date;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import com.glbk.utils.string.StringUtils;


public class DateUtils {

    /**
     * @param date      date
     * @param formatStr exg. yyyyMMddHHmmss,yyyy-MM-dd HH:mm:ss
     * @return date format String
     */
    public static String getFormatDate(Date date, String formatStr) {
        SimpleDateFormat sdf = new SimpleDateFormat(formatStr);
        return sdf.format(date);
    }

    /**
     * @param dateStr date format String
     * @param format  exg. yyyyMMddHHmmss,yyyy-MM-dd HH:mm:ss
     * @return date type
     */
    public static Date parseByFormat(String dateStr, String format) {
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        Date date = null;
        try {
            date = sdf.parse(dateStr);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return date;
    }


    /**
     * 自动判断判断输入格式
     *
     * @param dateStr date format String
     * @return
     */
    public static Date parse(String dateStr) {
        String ds = dateStr.replace(" ", "").replace("-", "").replace(":", "");
        if (ds.length() == 8)
            ds = ds + "000000";
        return parseByFormat(ds, "yyyyMMddHHmmss");
    }

    /**
     * 仿python relativedelta实现日期相加操作,返回String
     *
     * @param dateStr    支持yyyyMMddHHmmss和yyyy-MM-dd HH:mm:ss
     * @param offsetsStr delta日期的单位与长度,分隔符分别为逗号与等号
     *                   示例： offsetsStr："year=+2,month=8,day=-16"  年份加2，月份为8，日期减16
     *                   单位支持有year,month,day,hour,minute,second(或y,m,d,h,n,s)；
     *                   长度支持+N,-N,N
     * @return 返回String的格式与源参数dateStr相似；
     * dateDeltaStr("20180102030405","day=+3")   "20180105030405"
     * dateDeltaStr("2018-01-02 03:04:05","day=+3")   "2018-01-05 03:04:05"
     * dateDeltaStr("20180102","day=+3")   "20180105"
     * dateDeltaStr("2018-01-02","day=+3")   "2018-01-05"
     */
    public static String dateDeltaStr(String dateStr, String offsetsStr) {
        return dateDeltaStr(dateStr, offsetsStr, null);

    }


    /**
     * 仿python relativedelta实现日期相加操作,返回String
     * 示例： offsetsStr：[year=+2, month=8, day=-16]  年份加2，月份为8，日期减16
     *
     * @param dateStr    支持yyyyMMddHHmmss和yyyy-MM-dd HH:mm:ss
     * @param offsetsStr delta日期的单位与长度,分隔符分别为逗号与等号
     * @param formatStr  date format string exg.yyyyMMddHHmmss,yyyy-MM-dd HH:mm:ss
     *                   单位支持有year,month,day,hour,minute,second；
     *                   长度支持+N,-N,N
     *                   示例有year=+2, month=8, day=-16
     */
    public static String dateDeltaStr(String dateStr, String offsetsStr, String formatStr) {
        Date dt = relativedelta(dateStr, offsetsStr);
        if (StringUtils.isBlank(formatStr)) {
            String ds = dateStr.replace(" ", "").replace("-", "").replace(":", "");
            boolean f2 = dateStr.contains(" ") || dateStr.contains(":") || dateStr.contains("-"); // 包含[:-空格]任何一个
            if (ds.length() == 8) {//日期或日期+时间格式
                if (f2)
                    formatStr = "yyyy-MM-dd";
                else
                    formatStr = "yyyyMMdd";
            } else {
                if (f2)
                    formatStr = "yyyy-MM-dd HH:mm:ss";
                else
                    formatStr = "yyyyMMddHHmmss";
            }
        }
        return getFormatDate(dt, formatStr);
    }

    /**
     * 仿python relativedelta实现日期相加操作
     * 示例： offsetsStr：[year=+2, month=8, day=-16]  年份加2，月份为8，日期减16
     *
     * @param dateStr    支持yyyyMMddHHmmss和yyyy-MM-dd HH:mm:ss
     * @param offsetsStr delta日期的单位与长度,分隔符分别为逗号与等号
     *                   单位支持有year,month,day,hour,minute,second；
     *                   长度支持+N,-N,N
     *                   示例有year=+2, month=8, day=-16
     */
    public static Date relativedelta(String dateStr, String offsetsStr) {
        Date date = parse(dateStr);
        return relativedelta(date, offsetsStr);
    }


    /**
     * 仿python relativedelta实现日期相加操作
     * 示例： offsetsStr：[year=+2, month=8, day=-16]  年份加2，月份为8，日期减16
     *
     * @param date       日期类型
     * @param offsetsStr delta日期的单位与长度,分隔符分别为逗号与等号
     *                   单位支持有year,month,day,hour,minute,second；
     *                   长度支持+N,-N,N
     *                   示例有year=+2, month=8, day=-16
     *                   特殊(单个日期差)：-1,+1,1等价于day=-1,day=+1,day=1
     */
    public static Date relativedelta(Date date, String offsetsStr) {
        if (StringUtils.isNotBlank(offsetsStr)) {
            String[] offsets = null;
            if (offsetsStr.contains(",") || offsetsStr.contains("=")) {
                offsets = offsetsStr.split(",");
            } else {//offsetsStr形为-1,+1,1的，默认为日期
                offsets = ("day=" + offsetsStr).split(",");
            }

            return relativedelta(date, offsets);
        } else {
            return date;
        }
    }

    /**
     * 仿python relativedelta实现日期相加操作
     * 示例： offsets：[year=+2, month=8, day=-16]  年份加2，月份为8，日期减16
     *
     * @param date    日期类型
     * @param offsets delta日期的单位与长度。
     *                单位支持有year,month,day,hour,minute,second；
     *                长度支持+N,-N,N
     *                示例有year=+2, month=8, day=-16
     */
    public static Date relativedelta(Date date, String... offsets) {
        if (offsets == null || offsets.length == 0) {
            return date;
        } else {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);

            for (int i = 0; i < offsets.length; i++) {
                String[] offset = offsets[i].trim().split("=");
                int field = offsetUnit(offset[0].trim());
                String value = offset[1];
                if (value.contains("+") || value.contains("-")) {
                    cal.add(field, Integer.parseInt(offset[1]));
                } else {
                    int off;
                    if (field == Calendar.MONTH)
                        off = Integer.parseInt(offset[1]) - 1;
                    else
                        off = Integer.parseInt(offset[1]);
                    cal.set(field, off);
                }
            }
            return cal.getTime();
        }
    }

    /**
     * year,month,day,hour,minute,second字符串转化Calendar的常量INT类型
     *
     * @param offsetStr
     * @return
     */
    public static int offsetUnit(String offsetStr) {
        if (offsetStr.equals("year") || offsetStr.equals("y"))
            return Calendar.YEAR;
        else if (offsetStr.equals("month") || offsetStr.equals("m"))
            return Calendar.MONTH;
        else if (offsetStr.equals("day") || offsetStr.equals("d"))
            return Calendar.DAY_OF_MONTH;
        else if (offsetStr.equals("hour") || offsetStr.equals("h"))
            return Calendar.HOUR;
        else if (offsetStr.equals("minute") || offsetStr.equals("n"))
            return Calendar.MINUTE;
        else if (offsetStr.equals("second") || offsetStr.equals("s"))
            return Calendar.SECOND;
        else
            return -1;
    }

    /**
     * 日期比较
     *
     * @param date1
     * @param date2
     * @param interval 比较类型   year年  quarter季度  month月  week周  day天  hour小时  minute分  second秒
     * @return long类型
     */
    public static long dateDiff(Date date1, Date date2, String interval) {
        if ("year".equals(interval) || "y".equals(interval)) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date1);
            int time = calendar.get(Calendar.YEAR);
            calendar.setTime(date2);
            return time - calendar.get(Calendar.YEAR);
        }
        if ("quarter".equals(interval) || "q".equals(interval)) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date1);
            int time = calendar.get(Calendar.YEAR) * 4;
            calendar.setTime(date2);
            time -= calendar.get(Calendar.YEAR) * 4;
            calendar.setTime(date1);
            time += calendar.get(Calendar.MONTH) / 4;
            calendar.setTime(date2);
            return time - calendar.get(Calendar.MONTH) / 4;
        }
        if ("month".equals(interval) || "m".equals(interval)) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date1);
            int time = calendar.get(Calendar.YEAR) * 12;
            calendar.setTime(date2);
            time -= calendar.get(Calendar.YEAR) * 12;
            calendar.setTime(date1);
            time += calendar.get(Calendar.MONTH);
            calendar.setTime(date2);
            return time - calendar.get(Calendar.MONTH);
        }
        if ("week".equals(interval) || "w".equals(interval)) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date1);
            int time = calendar.get(Calendar.YEAR) * 52;
            calendar.setTime(date2);
            time -= calendar.get(Calendar.YEAR) * 52;
            calendar.setTime(date1);
            time += calendar.get(Calendar.WEEK_OF_YEAR);
            calendar.setTime(date2);
            return time - calendar.get(Calendar.WEEK_OF_YEAR);
        }
        if ("day".equals(interval) || "d".equals(interval)) {
            long time = date1.getTime() / 1000 / 60 / 60 / 24;
            return time - date2.getTime() / 1000 / 60 / 60 / 24;
        }
        if ("hour".equals(interval) || "h".equals(interval)) {
            long time = date1.getTime() / 1000 / 60 / 60;
            return time - date2.getTime() / 1000 / 60 / 60;
        }
        if ("minute".equals(interval) || "n".equals(interval)) {
            long time = date1.getTime() / 1000 / 60;
            return time - date2.getTime() / 1000 / 60;
        }
        if ("second".equals(interval) || "s".equals(interval)) {
            long time = date1.getTime() / 1000;
            return time - date2.getTime() / 1000;
        }
        return date1.getTime() - date2.getTime();
    }


    /**
     * 获取第一天
     *
     * @param date
     * @param dtype year quarter month week  注意一个星期的第一天是星期日
     * @return
     */
    public static String getFirstDay(Date date, String dtype) {
        if ("year".equals(dtype) || "y".equals(dtype)) {
            return getFormatDate(date, "yyyy") + "0101";
        }
        if ("quarter".equals(dtype) || "q".equals(dtype)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            int month = cal.get(Calendar.MONTH);
            month = (month / 3) * 3;
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            Date dt = cal.getTime();
            return getFormatDate(dt, "yyyyMMdd");
        }
        if ("month".equals(dtype) || "m".equals(dtype)) {
            return getFormatDate(date, "yyyyMM") + "01";
        }
        if ("week".equals(dtype) || "w".equals(dtype)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
            Date dt = cal.getTime();
            return getFormatDate(dt, "yyyyMMdd");
        }
        return null;
    }

    /**
     * 获取最后一天
     *
     * @param date
     * @param dtype
     * @return
     */
    public static String getLastDay(Date date, String dtype) {
        if ("year".equals(dtype) || "y".equals(dtype)) {
            return getFormatDate(date, "yyyy") + "1231";
        }
        if ("quarter".equals(dtype) || "q".equals(dtype)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            int month = cal.get(Calendar.MONTH);
            month = (month / 3) * 3;
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            Date dt = cal.getTime();
            dt = relativedelta(dt, "month=+3,day=-1");
            return getFormatDate(dt, "yyyyMMdd");
        }
        if ("month".equals(dtype) || "m".equals(dtype)) {
            Date dt = relativedelta(date, "month=+1,day=1,day=-1");
            return getFormatDate(dt, "yyyyMMdd");
        }
        if ("week".equals(dtype) || "w".equals(dtype)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY);
            Date dt = cal.getTime();
            return getFormatDate(dt, "yyyyMMdd");
        }
        return null;
    }

    /**
     * 获取有多少天
     * @param date 时间
     * @param dtype 类型
     * @return 天数
     */
    public static int getHowManyDay(Date date, String dtype) {
        if ("year".equals(dtype) || "y".equals(dtype)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            return cal.get(Calendar.DAY_OF_YEAR);
        }
        if ("quarter".equals(dtype) || "q".equals(dtype)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            int month = cal.get(Calendar.MONTH);
            month = (month / 3) * 3;
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            Date dt = cal.getTime();
            long time1 = date.getTime() / 1000 / 60 / 60 / 24;
            long time2 = dt.getTime() / 1000 / 60 / 60 / 24;
            return (int) (time1 - time2) + 1;
        }
        if ("month".equals(dtype) || "m".equals(dtype)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            return cal.get(Calendar.DAY_OF_MONTH);
        }
        if ("week".equals(dtype) || "w".equals(dtype)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            return cal.get(Calendar.DAY_OF_WEEK);
        }
        return 0;
    }

    public static void main(String[] args) {
        System.out.println("frist  20190101    == " + getFirstDay(parse("20190417"), "year"));
        System.out.println("frist  20190401    == " + getFirstDay(parse("20190417"), "quarter"));
        System.out.println("frist  20190401    == " + getFirstDay(parse("20190417"), "month"));
        System.out.println("frist  20190414    == " + getFirstDay(parse("20190417"), "week"));

        System.out.println("");
        System.out.println("last  20191231    == " + getLastDay(parse("20190417"), "year"));
        System.out.println("last  20190630    == " + getLastDay(parse("20190417"), "quarter"));
        System.out.println("last  20190430    == " + getLastDay(parse("20190417"), "month"));
        System.out.println("last  20190420    == " + getLastDay(parse("20190417"), "week"));

        System.out.println("");
        System.out.println("dateDiff    == " + dateDiff(parse("20190101"), parse("20190417"), "month"));
        System.out.println("dateDiff    == " + dateDiff(parse(getFirstDay(parse("20190417"), "year")), parse("20190417"), "day"));

        System.out.println("");
        System.out.println("last  getHowManyDay    == " + getHowManyDay(parse("20190417"), "year"));
        System.out.println("last  getHowManyDay    == " + getHowManyDay(parse("20190417"), "quarter"));
        System.out.println("last  getHowManyDay    == " + getHowManyDay(parse("20190417"), "month"));
        System.out.println("last  getHowManyDay    == " + getHowManyDay(parse("20190417"), "week"));

    }
//	public static void main(String[] args) {
//		String dt="20180102";
//		Date re = relativedelta(dt,"+1");
//		String reStr = getFormatDate(re,"yyyy-MM-dd HH:mm:ss");
//		//System.out.println(reStr); 
//		
//		String dt1=null;
//		dt1="20180102030405";
//		dt1="2018-01-02 03:04:05";
//		dt1="20180102";
//		String re1 = dateDeltaStr(dt1,"month=1,day=1,day=-1");
//		System.out.println(re1);
//		
//		
//	}


}

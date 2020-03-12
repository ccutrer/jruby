package org.jruby.util.time;

import org.joda.time.Chronology;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.IllegalFieldValueException;
import org.jruby.RubyBoolean;
import org.jruby.RubyFloat;
import org.jruby.RubyNumeric;
import org.jruby.RubyRational;
import org.jruby.RubyString;
import org.jruby.RubyTime;
import org.jruby.runtime.JavaSites;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

public class TimeArgs {
    final int year, month;
    final IRubyObject day, hour, minute, second, usec;
    final boolean dst;

    public TimeArgs(ThreadContext context, IRubyObject year, IRubyObject month, IRubyObject day, IRubyObject hour, IRubyObject minute, IRubyObject second, IRubyObject usec, boolean dst) {
        this.year = parseYear(context, year);
        this.month = parseMonth(context, month);
        this.day = parseIntArg(context, day);
        this.hour = parseIntArg(context, hour);
        this.minute = parseIntArg(context, minute);
        this.second = parseIntArg(context, second);
        this.usec = usec;
        this.dst = dst;
    }

    public TimeArgs(ThreadContext context, IRubyObject[] args) {
        IRubyObject nil = context.nil;

        IRubyObject year, month, day, hour, minute, second, usec;

        month = day = hour = minute = second = usec = nil;

        boolean dst = false;

        switch (args.length) {
            default: // zero args or more than 10 args
                throw new RuntimeException("BUG: should not get here");

            case 10:
                // smhDMY format
                if (args[8] instanceof RubyBoolean) dst = args[8].isTrue();

                year = args[5];
                month = args[4];
                day = args[3];
                hour = args[2];
                minute = args[1];
                second = args[0];
                usec = nil;
                break;

            // YMDhms formats
            case 8:
                // Ignores usec if 8 args (for compatibility with parse-date) or if not supplied.
                args[6] = nil;

            case 9:

            case 7: usec = args[6];
            case 6: second = args[5];
            case 5: minute = args[4];
            case 4: hour = args[3];
            case 3: day = args[2];
            case 2: month = args[1];
            case 1: year = args[0];
        }

        this.year = parseYear(context, year);
        this.month = parseMonth(context, month);
        this.day = parseIntArg(context, day);
        this.hour = parseIntArg(context, hour);
        this.minute = parseIntArg(context, minute);
        this.second = parseIntArg(context, second);
        this.usec = usec;
        this.dst = dst;
    }

    public static int parseYear(ThreadContext context, IRubyObject _year) {
        if (_year instanceof RubyString) {
            _year = RubyNumeric.str2inum(context.runtime, (RubyString) _year, 10, false);
        }

        return RubyNumeric.num2int(_year);
    }

    public static int parseMonth(ThreadContext context, IRubyObject _month) {
        if (_month.isNil()) return 1;

        int month;
        IRubyObject tmp = _month.checkStringType();

        if (!tmp.isNil()) {
            String monthStr = tmp.toString().toLowerCase();

            if (monthStr.length() == 3) {
                switch (monthStr) {
                    case "jan" : return  1;
                    case "feb" : return  2;
                    case "mar" : return  3;
                    case "apr" : return  4;
                    case "may" : return  5;
                    case "jun" : return  6;
                    case "jul" : return  7;
                    case "aug" : return  8;
                    case "sep" : return  9;
                    case "oct" : return 10;
                    case "nov" : return 11;
                    case "dec" : return 12;
                }
            }

            try {
                month = Integer.parseInt(monthStr);
            } catch (NumberFormatException ex) {
                throw context.runtime.newArgumentError("Argument out of range.");
            }
        } else {
            month = RubyNumeric.num2int(_month);
        }

        if (month < 1 || month > 12) {
            throw context.runtime.newArgumentError("Argument out of range: for month: " + month);
        }

        return month;
    }

    private static IRubyObject parseIntArg(ThreadContext context, IRubyObject arg) {
        if (arg == context.nil) return arg;

        if (arg instanceof RubyNumeric) return arg;

        final JavaSites.TimeSites sites = sites(context);

        if (sites.respond_to_to_int.respondsTo(context, arg, arg)) {
            return sites.to_int.call(context, arg, arg);
        }

        return sites.to_i.call(context, arg, arg);
    }

    private static DateTime adjustZoneOffset(DateTimeZone dtz, DateTime dt, boolean dst) {
        // If we're at a DST boundary, we need to choose the correct side of the boundary
        final DateTime beforeDstBoundary = dt.withEarlierOffsetAtOverlap();
        final DateTime afterDstBoundary = dt.withLaterOffsetAtOverlap();

        final int offsetBeforeBoundary = dtz.getOffset(beforeDstBoundary);
        final int offsetAfterBoundary = dtz.getOffset(afterDstBoundary);

        if (dst) {
            // If the time is during DST, we need to pick the time with the highest offset
            dt = offsetBeforeBoundary > offsetAfterBoundary ? beforeDstBoundary : afterDstBoundary;
        }
        else {
            dt = offsetBeforeBoundary > offsetAfterBoundary ? afterDstBoundary : beforeDstBoundary;
        }
        return dt;
    }

    public void initializeTime(ThreadContext context, RubyTime time, DateTimeZone dtz) {
        int day = this.day.isNil() ? 1 : RubyNumeric.num2int(this.day);
        int hour = this.hour.isNil() ? 0 : RubyNumeric.num2int(this.hour);
        int minute = this.minute.isNil() ? 0 : RubyNumeric.num2int(this.minute);
        int second = this.second.isNil() ? 0 : RubyNumeric.num2int(this.second);

        // Validate the times
        // Complying with MRI behavior makes it a little bit complicated. Logic copied from:
        // https://github.com/ruby/ruby/blob/trunk/time.c#L2609
        if (   (day < 1 || day > 31)
                || (hour < 0 || hour > 24)
                || (hour == 24 && (minute > 0 || second > 0))
                || (minute < 0 || minute > 59)
                || (second < 0 || second > 60)) {
            throw context.runtime.newArgumentError("argument out of range.");
        }

        long nanos = 0;
        int usecPart = 0;

        // set up with min values and then add to allow rolling over
        DateTime dt = new DateTime(year, month, 1, 0, 0, 0, 0, DateTimeZone.UTC);
        final Chronology chrono = dt.getChronology();
        long instant = dt.getMillis();

        try {
            instant = chrono.days().add(instant, day - 1);
            if (hour != 0) instant = chrono.hours().add(instant, hour);
            if (minute != 0) instant = chrono.minutes().add(instant, minute);
            if (second != 0) instant = chrono.seconds().add(instant, second);

            // 1.9 will observe fractional seconds *if* not given usec
            if (this.second != context.nil && usec == context.nil) {
                if (this.second instanceof RubyRational) {
                    RubyRational rat = (RubyRational) this.second;

                    if (rat.isNegative()) {
                        throw context.runtime.newArgumentError("argument out of range.");
                    }

                    RubyRational nsec = (RubyRational) rat.op_mul(context, context.runtime.newFixnum(1_000_000_000));

                    long full_nanos = nsec.getLongValue();
                    long millis = full_nanos / 1_000_000;

                    nanos = full_nanos - millis * 1_000_000;
                    instant = chrono.millis().add(instant, millis % 1000);
                } else {
                    double secs = RubyFloat.num2dbl(context, this.second);

                    if (secs < 0 || secs >= RubyTime.TIME_SCALE) {
                        throw context.runtime.newArgumentError("argument out of range.");
                    }

                    int int_millis = (int) (secs * 1000) % 1000;
                    instant = chrono.millis().add(instant, int_millis);
                    nanos = ((long) (secs * 1000000000) % 1000000);
                }
            }

            dt = dt.withMillis(instant);
            dt = dt.withZoneRetainFields(dtz);
            dt = adjustZoneOffset(dtz, dt, dst);
        } catch (IllegalFieldValueException e) {
            throw context.runtime.newArgumentError("time out of range");
        }

        if (usec != context.nil) {
            if (usec instanceof RubyRational) {
                RubyRational rat = (RubyRational) usec;

                if (rat.isNegative()) {
                    throw context.runtime.newArgumentError("argument out of range.");
                }

                RubyRational nsec = (RubyRational) rat.op_mul(context, context.runtime.newFixnum(1000));

                long tmpNanos = (long) nsec.getDoubleValue(context);

                dt = dt.withMillis(dt.getMillis() + (tmpNanos / 1_000_000));

                nanos = tmpNanos % 1_000_000;
            } else if (usec instanceof RubyFloat) {
                RubyFloat flo = (RubyFloat) usec;

                if (flo.isNegative()) {
                    throw context.runtime.newArgumentError("argument out of range.");
                }

                double micros = flo.getDoubleValue();

                dt = dt.withMillis(dt.getMillis() + (long) (micros / 1000));

                nanos = (long) Math.rint((micros * 1000) % 1_000_000);
            } else {
                int usec = parseIntArg(context, this.usec).isNil() ? 0 : RubyNumeric.num2int(this.usec);

                if (usec < 0 || usec >= RubyTime.TIME_SCALE / 1000) {
                    throw context.runtime.newArgumentError("argument out of range.");
                }

                usecPart = usec % 1000;
                int msecPart = usec / 1000;

                if (usec < 0) {
                    msecPart -= 1;
                    usecPart += 1000;
                }

                dt = dt.withMillis(dt.getMillis() + msecPart);
            }
        }

        time.setDateTime(dt);

        if (usecPart != 0) time.setUSec(usecPart);
        if (nanos != 0) time.setNSec(nanos);
    }

    private static JavaSites.TimeSites sites(ThreadContext context) {
        return context.sites.Time;
    }
}

package com.googlecode.greysanatomy.command;

import com.googlecode.greysanatomy.agent.GreysAnatomyClassFileTransformer.TransformResult;
import com.googlecode.greysanatomy.command.annotation.Cmd;
import com.googlecode.greysanatomy.command.annotation.IndexArg;
import com.googlecode.greysanatomy.command.annotation.NamedArg;
import com.googlecode.greysanatomy.command.view.TableView;
import com.googlecode.greysanatomy.probe.Advice;
import com.googlecode.greysanatomy.probe.AdviceListenerAdapter;
import com.googlecode.greysanatomy.server.GaSession;
import com.googlecode.greysanatomy.util.GaStringUtils;

import java.lang.instrument.Instrumentation;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static com.googlecode.greysanatomy.agent.GreysAnatomyClassFileTransformer.transform;
import static com.googlecode.greysanatomy.probe.ProbeJobs.activeJob;

/**
 * 监控请求命令<br/>
 * 输出的内容格式为:<br/>
 * <style type="text/css">
 * table, th, td {
 * border:1px solid #cccccc;
 * border-collapse:collapse;
 * }
 * </style>
 * <table>
 * <tr>
 * <th>时间戳</th>
 * <th>统计周期(s)</th>
 * <th>类全路径</th>
 * <th>方法名</th>
 * <th>调用总次数</th>
 * <th>成功次数</th>
 * <th>失败次数</th>
 * <th>平均耗时(ms)</th>
 * <th>失败率</th>
 * </tr>
 * <tr>
 * <td>2012-11-07 05:00:01</td>
 * <td>120</td>
 * <td>com.taobao.item.ItemQueryServiceImpl</td>
 * <td>queryItemForDetail</td>
 * <td>1500</td>
 * <td>1000</td>
 * <td>500</td>
 * <td>15</td>
 * <td>30%</td>
 * </tr>
 * <tr>
 * <td>2012-11-07 05:00:01</td>
 * <td>120</td>
 * <td>com.taobao.item.ItemQueryServiceImpl</td>
 * <td>queryItemById</td>
 * <td>900</td>
 * <td>900</td>
 * <td>0</td>
 * <td>7</td>
 * <td>0%</td>
 * </tr>
 * </table>
 *
 * @author vlinux
 */
@Cmd(named = "monitor", sort = 2, desc = "Buried point method for monitoring the operation.")
public class MonitorCommand extends Command {

    @IndexArg(index = 0, name = "class-pattern", summary = "pattern matching of classpath.classname")
    private String classPattern;

    @IndexArg(index = 1, name = "method-pattern", summary = "pattern matching of method name")
    private String methodPattern;

    @NamedArg(named = "c", hasValue = true, description = "the cycle of output")
    private int cycle = 120;

    @NamedArg(named = "S", description = "including sub class")
    private boolean isSuper = false;

    @NamedArg(named = "E", description = "enable the regex pattern matching")
    private boolean isRegEx = false;

    /**
     * 命令是否启用正则表达式匹配
     *
     * @return true启用正则表达式/false不启用
     */
    public boolean isRegEx() {
        return isRegEx;
    }

    /*
     * 输出定时任务
     */
    private Timer timer;

    /*
     * 监控数据
     */
    private ConcurrentHashMap<Key, AtomicReference<Data>> monitorData = new ConcurrentHashMap<Key, AtomicReference<Data>>();

    /**
     * 数据监控用的Key
     *
     * @author vlinux
     */
    private static class Key {
        private final String className;
        private final String behaviorName;

        private Key(String className, String behaviorName) {
            this.className = className;
            this.behaviorName = behaviorName;
        }

        @Override
        public int hashCode() {
            return className.hashCode() + behaviorName.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj
                    || !(obj instanceof Key)) {
                return false;
            }
            Key okey = (Key) obj;
            return GaStringUtils.equals(okey.className, className) && GaStringUtils.equals(okey.behaviorName, behaviorName);
        }

    }

    /**
     * 数据监控用的value
     *
     * @author vlinux
     */
    private static class Data {
        private int total;
        private int success;
        private int failed;
        private long cost;
    }

    @Override
    public Action getAction() {
        return new Action() {

            @Override
            public void action(final GaSession gaSession, final Info info, final Sender sender) throws Throwable {

                final Instrumentation inst = info.getInst();
                final TransformResult result = transform(inst, classPattern, methodPattern, isSuper, isRegEx(), new AdviceListenerAdapter() {

                    private final ThreadLocal<Long> beginTimestamp = new ThreadLocal<Long>();

                    @Override
                    public void onBefore(Advice p) {
                        beginTimestamp.set(System.currentTimeMillis());
                    }

                    @Override
                    public void onFinish(Advice p) {
                        final Long startTime = beginTimestamp.get();
                        if (null == startTime) {
                            return;
                        }
                        final long cost = System.currentTimeMillis() - startTime;
                        final Key key = new Key(p.getTarget().getTargetClassName(), p.getTarget().getTargetBehaviorName());

                        while (true) {
                            AtomicReference<Data> value = monitorData.get(key);
                            if (null == value) {
                                monitorData.putIfAbsent(key, new AtomicReference<Data>(new Data()));
                                continue;
                            }

                            while (true) {
                                Data oData = value.get();
                                Data nData = new Data();
                                nData.cost = oData.cost + cost;
                                if (p.isThrowException()) {
                                    nData.failed = oData.failed + 1;
                                }
                                if (p.isReturn()) {
                                    nData.success = oData.success + 1;
                                }
                                nData.total = oData.total + 1;
                                if (value.compareAndSet(oData, nData)) {
                                    break;
                                }
                            }

                            break;
                        }
                    }

                    @Override
                    public void create() {
                        timer = new Timer("Timer-for-greys-monitor-" + info.getJobId(), true);
                        timer.scheduleAtFixedRate(new TimerTask() {

                            @Override
                            public void run() {
                                if (monitorData.isEmpty()) {
                                    return;
                                }
                                final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

                                final TableView tableView = new TableView(8)
                                        .addRow(
                                                "timestamp",
                                                "class",
                                                "behavior",
                                                "total",
                                                "success",
                                                "fail",
                                                "rt",
                                                "fail-rate"
                                        );

                                for (Map.Entry<Key, AtomicReference<Data>> entry : monitorData.entrySet()) {
                                    final AtomicReference<Data> value = entry.getValue();

                                    Data data;
                                    while (true) {
                                        data = value.get();
                                        if (value.compareAndSet(data, new Data())) {
                                            break;
                                        }
                                    }

                                    if (null != data) {

                                        final DecimalFormat df = new DecimalFormat("0.00");

                                        tableView.addRow(
                                                timestamp,
                                                entry.getKey().className,
                                                entry.getKey().behaviorName,
                                                data.total,
                                                data.success,
                                                data.failed,
                                                df.format(div(data.cost, data.total)),
                                                df.format(100.0d * div(data.failed, data.total))
                                        );

                                    }
                                }

                                tableView.setPadding(1);
                                tableView.setDrawBorder(true);

                                sender.send(false, tableView.draw()+"\n");
                            }

                        }, 0, cycle * 1000);
                    }

                    private double div(double a, double b) {
                        if (b == 0) {
                            return 0;
                        }
                        return a / b;
                    }

                    @Override
                    public void destroy() {
                        if (null != timer) {
                            timer.cancel();
                        }
                    }

                }, info, false);

                // 激活任务
                activeJob(result.getId());

                final StringBuilder message = new StringBuilder();
                message.append(GaStringUtils.LINE);
                message.append(String.format("result: matching-class=%s,matching-method=%s.\n",
                        result.getModifiedClasses().size(),
                        result.getModifiedBehaviors().size()));
                message.append(GaStringUtils.ABORT_MSG).append("\n");
                sender.send(false, message.toString());
            }

        };
    }


}

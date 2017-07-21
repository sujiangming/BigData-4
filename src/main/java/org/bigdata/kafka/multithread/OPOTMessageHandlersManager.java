package org.bigdata.kafka.multithread;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Created by hjq on 2017/6/19.
 * OPOT ==> one partition one Thread
 */
public class OPOTMessageHandlersManager extends AbstractMessageHandlersManager{
    private static Logger log = LoggerFactory.getLogger(OPOTMessageHandlersManager.class);
    private Map<TopicPartition, OPOTMessageQueueHandlerThread> topicPartition2Thread = new ConcurrentHashMap<>();
    private ThreadPoolExecutor threads = new ThreadPoolExecutor(2, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());

    @Override
    public boolean dispatch(ConsumerRecordInfo consumerRecordInfo, Map<TopicPartition, OffsetAndMetadata> pendingOffsets){
        log.debug("dispatching message: " + StrUtil.consumerRecordDetail(consumerRecordInfo.record()));

        if(isRebalance.get()){
            log.debug("dispatch failure ~~~ rebalancing...");
            return false;
        }

        TopicPartition topicPartition = consumerRecordInfo.topicPartition();
        if(topicPartition2Thread.containsKey(topicPartition)){
            //已有该topic分区对应的线程启动
            //直接添加队列
            topicPartition2Thread.get(topicPartition).queue().add(consumerRecordInfo);
            log.debug("message: " + StrUtil.consumerRecordDetail(consumerRecordInfo.record()) + "queued(" + topicPartition2Thread.get(topicPartition).queue().size() + " rest)");
        }
        else{
            //没有该topic分区对应的线程'
            //先启动线程,再添加至队列
            OPOTMessageQueueHandlerThread thread = newThread(pendingOffsets, topicPartition.topic() + "-" + topicPartition.partition(), newMessageHandler(topicPartition.topic()), newCommitStrategy(topicPartition.topic()));
            topicPartition2Thread.put(topicPartition, thread);
            thread.queue().add(consumerRecordInfo);
            runThread(thread);
            log.debug("message: " + StrUtil.consumerRecordDetail(consumerRecordInfo.record()) + "queued(" + thread.queue.size() + " rest)");
        }

        return true;
    }

    private boolean checkHandlerTerminated(){
        for(OPOTMessageQueueHandlerThread thread: topicPartition2Thread.values()){
            if(!thread.isTerminated()){
                return false;
            }
        }
        log.info("all handlers terminated");
        return true;
    }

    @Override
    public void consumerCloseNotify(Set<TopicPartition> topicPartitions){
        log.info("shutdown all handlers...");
        //停止所有handler
        for(OPOTMessageQueueHandlerThread thread: topicPartition2Thread.values()){
            thread.stop();
        }

        //等待所有handler完成,超过10s,强制关闭
        int count = 0;
        while(!checkHandlerTerminated() && count < 5){
            count ++;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        //关闭线程池
        if(count < 5){
            log.info("shutdown thread pool...");
            threads.shutdown();
        }
        else{
            log.info("force shutdown thread pool...");
            threads.shutdownNow();
        }
        log.info("thread pool terminated");
    }

    @Override
    public void consumerRebalanceNotify(){
        isRebalance.set(true);
        log.info("clean up handlers(not thread)");

        //关闭Handler执行,但不关闭线程,达到线程复用的效果
        for(OPOTMessageQueueHandlerThread thread: topicPartition2Thread.values()){
            thread.stop();
        }

        //清楚topic分区与handler的映射
        topicPartition2Thread.clear();
        isRebalance.set(false);
    }

    private OPOTMessageQueueHandlerThread newThread(Map<TopicPartition, OffsetAndMetadata> pendingOffsets, String logHead, MessageHandler messageHandler, CommitStrategy commitStrategy){
        return new OPOTMessageQueueHandlerThread(logHead, pendingOffsets, messageHandler, commitStrategy);
    }

    private void runThread(Runnable target){
        threads.submit(target);
    }

    private final class OPOTMessageQueueHandlerThread extends AbstractMessageHandlersManager.MessageQueueHandlerThread {

        public OPOTMessageQueueHandlerThread(String LOG_HEAD, Map<TopicPartition, OffsetAndMetadata> pendingOffsets, MessageHandler messageHandler, CommitStrategy commitStrategy) {
            super(LOG_HEAD, pendingOffsets, messageHandler, commitStrategy);
        }

        @Override
        protected void preTerminated() {
            //只有两种情况:
            //1:rebalance 抛弃这些待处理信息
            //2:关闭consumer 抛弃这些待处理信息,提交最近处理的offset
            if(!isRebalance.get()){
                log.info(LOG_HEAD() + " closing consumer should commit last offsets sync now");
                if(lastRecord != null){
                    pendingOffsets.put(new TopicPartition(lastRecord.topic(), lastRecord.partition()), new OffsetAndMetadata(lastRecord.offset() + 1));
                }
            }
            super.preTerminated();
        }

    }
}

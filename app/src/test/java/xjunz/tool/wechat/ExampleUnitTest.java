/*
 * Copyright (c) 2020 xjunz. 保留所有权利
 */
package xjunz.tool.wechat;

import org.junit.Test;
import org.reactivestreams.Publisher;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import xjunz.tool.wechat.util.ShellUtils;

import static org.junit.Assert.assertEquals;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(new int[]{1, 2}, new int[]{1, 2});
        assertEquals(4, 2 + 2);
    }

    @Test
    public void testXmlParse() {
        MessageParser.parseJoinGroupMessage("");
    }

    @Test
    public void testRoot() throws ShellUtils.ShellException {
        String out = ShellUtils.cat("/data/user/0/com.tencent.mm/shared_prefs/app_brand_global_sp.xml", "test");
    }

    @Test
    public void testParallel() {
        final long[] start = new long[1];
        ExecutorService fixedThreadPool = Executors.newFixedThreadPool(3);
     /*   Flowable.fromArray(1, 2, 3).parallel().map(integer -> {
            Thread.sleep(1000);
            return integer;
        }).runOn(Schedulers.computation()).sequential().forEach(integer -> {
            System.out.println(integer + ":" + (Thread.currentThread()));
        });*/
        Flowable.fromArray(1, 2, 3).flatMap(new Function<Integer, Publisher<Integer>>() {
            @Override
            public Publisher<Integer> apply(@NonNull Integer integer) throws Exception {
                return Flowable.create(new FlowableOnSubscribe<Integer>() {
                    @Override
                    public void subscribe(@NonNull FlowableEmitter<Integer> emitter) throws Exception {
                        Thread.sleep(1);
                        System.out.println(integer + " next:" + (Thread.currentThread()));
                        emitter.onNext(integer);
                    }
                }, BackpressureStrategy.BUFFER).subscribeOn(Schedulers.computation());
            }
        }).subscribe(new Consumer<Integer>() {
            @Override
            public void accept(Integer integer) throws Exception {
                // System.out.println(integer + " next:" + (Thread.currentThread()));
            }
        }, new Consumer<Throwable>() {

            @Override
            public void accept(Throwable throwable) throws Exception {
                System.out.println(" error:" + (Thread.currentThread()));
            }
        });
    }

    @Test
    public void testString() {
        String wxid = "wxid_unknown";
        String raw = "wxid_unknown:<hello:wxid_unknown>";
        int index = raw.indexOf(':');
        String newStr = "wxid_new" + raw.substring(index + wxid.length());
        System.out.println(raw.substring(0, index));

    }

    private class A {
        String b = "xjunz";
        WeakReference<String> weakB = new WeakReference<>("xjunz");
    }

    @Test
    public void testEdition() {
        EditionProfile editionProfile = new EditionProfile();
        editionProfile.addAddition(1, 1);
        editionProfile.addAddition(5, 12);
        editionProfile.addAddition(3, 3);
        editionProfile.addDeletion(2, 10);
        editionProfile.addDeletion(21, 21);
        editionProfile.removeAddition(8, 10);
    }


    private static class EditionProfile {
        private List<Integer> deletionNodes;
        private List<Integer> additionNodes;

        private EditionProfile() {
            deletionNodes = new ArrayList<>();
            additionNodes = new ArrayList<>();
        }


        private void addDeletion(int start, int end) {
            int index = Collections.binarySearch(deletionNodes, start);
            int insertion = index >= 0 ? index : -(index + 1);
            deletionNodes.add(insertion, start);
            deletionNodes.add(insertion + 1, end);
        }

        private void addAddition(int start, int end) {
            int index = Collections.binarySearch(additionNodes, start);
            int insertion = index >= 0 ? index : -(index + 1);
            additionNodes.add(insertion, start);
            additionNodes.add(insertion + 1, end);
        }

        private void removeDeletion(int start, int end) {
            deletionNodes.remove(Integer.valueOf(start));
            deletionNodes.remove(Integer.valueOf(end));
        }

        private void removeAddition(int start, int end) {
            int startIndex = Collections.binarySearch(additionNodes, start);
            int endIndex = Collections.binarySearch(additionNodes, end);
            if (startIndex >= 0) {
                if (endIndex >= 0) {
                    additionNodes.remove(Integer.valueOf(start - 1));
                    additionNodes.remove(Integer.valueOf(end + 1));
                } else {
                    additionNodes.set(startIndex, end + 1);
                }
            } else {
                if (endIndex >= 0) {
                    additionNodes.set(endIndex, start - 1);
                } else {
                    int insertion = -(startIndex + 1);
                    additionNodes.add(insertion, start - 1);
                    additionNodes.add(insertion + 1, end + 1);
                }
            }
        }

    }
}
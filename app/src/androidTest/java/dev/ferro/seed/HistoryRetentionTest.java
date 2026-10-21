package dev.ferro.seed;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Android filesystem acceptance using only a unique cache fixture, never shared user history. */
@RunWith(AndroidJUnit4.class)
public final class HistoryRetentionTest {
    @Test public void retainsLatestHundredAndFavoritesAcrossRestartUnlikeAndFailedMigration() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(context.getCacheDir(),"retention-test-"+UUID.randomUUID());
        assertTrue("Create isolated fixture directory",directory.mkdir());
        try {
            File history=new File(directory,"history.json");
            List<NoteRecord> fixtures=new ArrayList<>();
            for(int i=0;i<105;i++)fixtures.add(record("u"+i,1000+i,false,i));
            NoteRecord oldestFavorite=record("favorite-old",10,true,7);
            NoteRecord newestFavorite=record("favorite-new",5000,true,8);
            NoteRecord middleFavorite=record("favorite-middle",1050,true,9);
            fixtures.add(oldestFavorite);fixtures.add(newestFavorite);fixtures.add(middleFavorite);
            byte[] oversized=payload(fixtures,false);
            Files.write(history.toPath(),oversized);

            HistoryStore loaded=new HistoryStore(history);
            assertCounts(loaded,100,3);
            for(int i=0;i<105;i++){
                if(i<5)assertNull("Only oldest unliked records are removed",loaded.get("u"+i));
                else assertRecord(fixtures.get(i),loaded.get("u"+i));
            }
            assertRecord(oldestFavorite,loaded.get(oldestFavorite.id));
            assertRecord(newestFavorite,loaded.get(newestFavorite.id));
            assertRecord(middleFavorite,loaded.get(middleFavorite.id));
            assertArrayEquals("Successful trim retains the original complete backup",oversized,
                    Files.readAllBytes(new File(history.getPath()+".bak").toPath()));
            assertEquals(103,persistedCount(history));

            HistoryStore restarted=new HistoryStore(history);
            assertCounts(restarted,100,3);
            restarted.add(record("u105",1105,false,105));
            assertNull("Adding a new result evicts the previous oldest unliked",restarted.get("u5"));
            assertCounts(restarted,100,3);

            NoteRecord unlikedNew=restarted.toggleLike(newestFavorite.id);
            assertNotNull("A newly unliked recent favorite is retained",unlikedNew);
            assertFalse(unlikedNew.liked);
            assertRecord(newestFavorite.withLiked(false),unlikedNew);
            assertNull("Recent unlike must evict the oldest ordinary record",restarted.get("u6"));
            assertCounts(restarted,100,2);

            assertNull("An old favorite outside the latest hundred is removed when unliked",
                    restarted.toggleLike(oldestFavorite.id));
            assertNull(restarted.get(oldestFavorite.id));
            assertCounts(restarted,100,1);
            HistoryStore finalRestart=new HistoryStore(history);
            assertCounts(finalRestart,100,1);
            assertRecord(middleFavorite,finalRestart.get(middleFavorite.id));
            assertRecord(newestFavorite.withLiked(false),finalRestart.get(newestFavorite.id));
            assertEquals(newestFavorite.id,finalRestart.latest().id);
            for(int i=7;i<=105;i++)assertNotNull(finalRestart.get("u"+i));
            assertEquals(1,HistoryStore.newestUnlikedIndex(finalRestart.sortedHistory()));
            assertEquals(101,persistedCount(history));

            // A failed version-1 retention migration must not discard or rewrite original data.
            File legacy=new File(directory,"legacy.json");
            List<NoteRecord> oldRecords=new ArrayList<>();
            for(int i=0;i<101;i++)oldRecords.add(record("legacy-"+i,100+i,false,i));
            oldRecords.add(record("legacy-favorite",1,true,2));
            byte[] original=payload(oldRecords,true);
            Files.write(legacy.toPath(),original);
            File blockedTemporary=new File(legacy.getPath()+".tmp");
            assertTrue(blockedTemporary.mkdir());
            try {
                new HistoryStore(legacy);
                fail("Retention migration must report its failed durable write");
            } catch(IOException expected) {
                assertArrayEquals(original,Files.readAllBytes(legacy.toPath()));
                assertEquals(102,persistedCount(legacy));
                assertFalse("Failed initial write must not create a replacement backup",
                        new File(legacy.getPath()+".bak").exists());
            }
            assertTrue(blockedTemporary.delete());
            HistoryStore recovered=new HistoryStore(legacy);
            assertCounts(recovered,100,1);
            assertNull(recovered.get("legacy-0"));
            NoteRecord favorite=recovered.get("legacy-favorite");
            assertNotNull(favorite);assertTrue(favorite.liked);
            assertArrayEquals(new int[]{50,50},favorite.tones);
            assertArrayEquals(new int[]{80,80},favorite.decays);
            assertArrayEquals(new int[]{20,20},favorite.drives);
            assertArrayEquals(original,Files.readAllBytes(new File(legacy.getPath()+".bak").toPath()));
            assertCounts(new HistoryStore(legacy),100,1);
        } finally {
            deleteFixture(directory);
        }
    }

    private static NoteRecord record(String id,long time,boolean liked,int index){
        return new NoteRecord(id,time,new int[]{48,67},new int[]{16+index%112,127-index%112},
                new int[]{index%101,100-index%101},new int[]{index*3%101,0},new int[]{0,index*7%101},
                liked,index,"retention-fixture");
    }

    private static byte[] payload(List<NoteRecord> records,boolean legacy) throws Exception {
        JSONArray array=new JSONArray();
        for(NoteRecord record:records){
            JSONObject value=record.toJson();
            if(legacy){value.remove("tones");value.remove("decays");value.remove("drives");}
            array.put(value);
        }
        return new JSONObject().put("version",legacy?1:2).put("records",array)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int persistedCount(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8))
                .getJSONArray("records").length();
    }

    private static void assertCounts(HistoryStore store,int unliked,int liked){
        int actualLiked=0,actualUnliked=0;
        for(NoteRecord record:store.list()){if(record.liked)actualLiked++;else actualUnliked++;}
        assertEquals("Latest ordinary-history count",unliked,actualUnliked);
        assertEquals("All favorites remain outside the ordinary-history cap",liked,actualLiked);
    }

    private static void assertRecord(NoteRecord expected,NoteRecord actual){
        assertNotNull(actual);assertEquals(expected.id,actual.id);assertEquals(expected.createdAt,actual.createdAt);
        assertEquals(expected.liked,actual.liked);assertEquals(expected.seed,actual.seed);
        assertEquals(expected.modelVersion,actual.modelVersion);assertArrayEquals(expected.pitches,actual.pitches);
        assertArrayEquals(expected.velocities,actual.velocities);assertArrayEquals(expected.tones,actual.tones);
        assertArrayEquals(expected.decays,actual.decays);assertArrayEquals(expected.drives,actual.drives);
    }

    private static void deleteFixture(File file) throws IOException {
        if(file.isDirectory()){
            File[] children=file.listFiles();
            if(children==null)throw new IOException("Cannot inspect test fixture for cleanup");
            for(File child:children)deleteFixture(child);
        }
        if(file.exists()&&!file.delete())throw new IOException("Cannot remove test fixture: "+file.getName());
    }
}

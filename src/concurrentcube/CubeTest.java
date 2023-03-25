package concurrentcube;

import static org.junit.Assert.*;
import org.junit.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static java.lang.Thread.sleep;

public class CubeTest {
    private Cube cube;

    private final int THREADPOOL_SIZE = 64;
    private final int SIDES = 6;

    private class Rotator implements Runnable {

        private final int side;
        private final int layer;

        private Rotator(int side, int layer) {
            this.side = side;
            this.layer = layer;
        }

        @Override
        public void run() {
            try {
                cube.rotate(side, layer);
            } catch (InterruptedException e) {
                System.out.println("Rotator for side " + side + " and layer " + layer + " interrupted.");
            }
        }
    }

    private class Shower implements Callable<String> {

        private Shower() {}

        @Override
        public String call() throws InterruptedException {
            return cube.show();
        }
    }

    /* Test sprawdzający, czy liczba wykonanych BeforeRotation = liczba wykonanych AfterRotation,
       czy liczba wykonanych BeforeShowing = liczba wykonanych AfterShowing, oraz czy liczba
       wykonanych BeforeRotation + BeforeShowing = liczba wykonanych AfterRotation + AfterShowing. */
    @Test
    public void threadCounterTest() {
        int size = 10;
        AtomicInteger counterRotate = new AtomicInteger(0);
        AtomicInteger counterShow = new AtomicInteger(0);
        AtomicInteger counterBoth = new AtomicInteger(0);

        cube = new Cube(size,
                (x, y) -> {
                    counterRotate.incrementAndGet();
                    counterBoth.incrementAndGet();
                },
                (x, y) -> {
                    counterRotate.decrementAndGet();
                    counterBoth.decrementAndGet();
                },
                () -> {
                    counterShow.incrementAndGet();
                    counterBoth.incrementAndGet();
                },
                () -> {
                    counterShow.decrementAndGet();
                    counterBoth.decrementAndGet();
                }
        );

        int THREADS = 1000;

        List<Thread> threads = new ArrayList<>();
        ExecutorService showerPool = Executors.newFixedThreadPool(THREADPOOL_SIZE);
        List<Callable<String>> shows = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            shows.add(new Shower());
        }

        threads.add(new Thread(() -> {
            try {
                showerPool.invokeAll(shows);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }));

        for (int i = 0; i < THREADS; i++) {
            Runnable runnable = new Rotator(cube.getRandomSide(), cube.getRandomLayer());
            Thread thread = new Thread(runnable);
            threads.add(thread);
        }

        for (Thread thread : threads) {
            thread.start();
        }
        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        assertEquals(0, counterRotate.get());
        assertEquals(0, counterShow.get());
        assertEquals(0, counterBoth.get());
    }

    /* Test sprawdzający, czy stan kostki uzyskanej po współbieżnie wykonanych losowych obrotach
       odpowiada jakiemuś stanu kostki dokonującej jednej z permutacji ciągu tych obrotów
       sekwencyjnie, oraz czy współbieżnie obroty te wykonują się krócej. */
    @Test
    public void rotateCorrectnessTest() throws InterruptedException {
        int size = 3;
        cube = new Cube(size,
                (x, y) -> {
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                (x, y) -> {},
                () -> {
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                () -> {}
        );

        /* Liczba permutacji to THREADS!, więc musi być względnie mała, żeby długość
           wykonywania testu była akceptowalna. */
        int THREADS = 6;

        List<Thread> threads = new ArrayList<>();
        List<Rotation> rotations = new ArrayList<>();
        ExecutorService showerPool = Executors.newFixedThreadPool(THREADPOOL_SIZE);
        List<Callable<String>> showers = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            showers.add(new Shower());
        }

        threads.add(new Thread(() -> {
            try {
                showerPool.invokeAll(showers);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }));

        for (int i = 0; i < THREADS; i++) {
            int side = cube.getRandomSide();
            int layer = cube.getRandomLayer();
            Runnable runnable = new Rotator(side, layer);
            Thread thread = new Thread(runnable);
            threads.add(thread);
            rotations.add(new Rotation(side, layer));
        }

        Stopwatch stopwatch = new Stopwatch();
        stopwatch.start();
        for (Thread thread : threads) {
            thread.start();
        }
        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        String cubeState = cube.show();
        Duration concurrentDuration = stopwatch.stop();

        Cube sequentialCube = new Cube(size,
                (x, y) -> {
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                (x, y) -> {},
                () -> {
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                () -> {}
        );

        List<List<Rotation>> permutations = generatePermutations(rotations);
        Duration sequentialDuration = null;
        boolean found = false;
        for (List<Rotation> permutation : permutations) {
            sequentialCube.reset();
            stopwatch.start();
            for (Rotation rotation : permutation) {
                sequentialCube.rotate(rotation.side, rotation.layer);
                sequentialCube.show();
            }
            String currentSequentialCubeState = sequentialCube.show();
            sequentialDuration = stopwatch.stop();
            if (cubeState.equals(currentSequentialCubeState)) {
                found = true;
                break;
            }
        }
        assertTrue(found);
        assert(concurrentDuration.compareTo(sequentialDuration) < 0);
    }

    /* Test bezpieczeństwa sprawdzający, czy po co 10 obrotach stan kostki jest prawidłowy -
       czy kwadratów każdego koloru jest size * size. */
    @Test
    public void safetyTest() throws InterruptedException, ExecutionException {
        int size = 100;
        cube = new Cube(size,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        int THREADS = 1000;

        List<Thread> threads = new ArrayList<>();
        ExecutorService showerPool = Executors.newFixedThreadPool(THREADPOOL_SIZE);

        for (int i = 0; i < THREADS; i++) {
            Runnable runnable = new Rotator(cube.getRandomSide(), cube.getRandomLayer());
            Thread thread = new Thread(runnable);
            threads.add(thread);
        }

        for (int i = 0; i < THREADS; i++) {
            threads.get(i).start();
            if (i % 10 == 0) {
                Future<String> result = showerPool.submit(new Shower());
                String cubeState = result.get();
                for (int side = 0; side < SIDES; side++) {
                    int sideColor = Character.forDigit(side, 10);
                    long count = cubeState.chars().filter(ch -> ch == sideColor).count();
                    assertEquals(size * size, count);
                }
            }
        }
        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /* Test sprawdzający, czy dana sekwencja obrotów współbieżnie wykonuje się szybciej niż
       sekwencyjnie. */
    @Test
    public void concurrentSpeedTest() throws InterruptedException {
        int size = 10;

        cube = new Cube(size,
                (x, y) -> {
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                (x, y) -> {},
                () -> {
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                () -> {}
        );

        Cube sequentialCube = new Cube(size,
                (x, y) -> {
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                (x, y) -> {},
                () -> {
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                () -> {}
        );

        int THREADS = 1000;

        Stopwatch stopwatch = new Stopwatch();
        Duration concurrentDuration;
        Duration sequentialDuration;

        int ROTATE_GROUPS = 3;
        for (int i = 0; i < ROTATE_GROUPS; i++) {
            List<Thread> threads = new ArrayList<>();
            for (int j = 0; j < THREADS; j++) {
                int side = i;
                if (j % 2 == 0) side = cube.getOppositeSide(side);
                int layer = j % size;
                Runnable runnable = new Rotator(side, layer);
                Thread thread = new Thread(runnable);
                threads.add(thread);
            }

            stopwatch.start();
            for (Thread thread : threads) {
                thread.start();
            }
            try {
                for (Thread thread : threads) {
                    thread.join();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            concurrentDuration = stopwatch.stop();

            stopwatch.start();
            for (int j = 0; j < THREADS; j++) {
                int side = i;
                if (j % 2 == 0) side = cube.getOppositeSide(side);
                int layer = j % size;
                sequentialCube.rotate(side, layer);
            }
            sequentialDuration = stopwatch.stop();

            assert(concurrentDuration.compareTo(sequentialDuration) < 0);
        }

        ExecutorService showerPool = Executors.newFixedThreadPool(THREADPOOL_SIZE);
        List<Callable<String>> showers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            showers.add(new Shower());
        }

        stopwatch.start();
        showerPool.invokeAll(showers);
        concurrentDuration = stopwatch.stop();

        stopwatch.start();
        for (int i = 0; i < THREADS; i++) {
            sequentialCube.show();
        }
        sequentialDuration = stopwatch.stop();

        assert(concurrentDuration.compareTo(sequentialDuration) < 0);
    }

    /* Test sprawdzający, czy po ciągu tylu samych przeciwnych obrotów na danej warstwie
       w jednej płaszczyźnie kostka będzie ułożona. */
    @Test
    public void twoAntagonistRotationsTest() throws InterruptedException {
        int size = 100;
        AtomicInteger counterRotate = new AtomicInteger(0);
        cube = new Cube(size,
                (x, y) -> counterRotate.incrementAndGet(),
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        int THREADS = 10000;

        ExecutorService rotatorPool = Executors.newFixedThreadPool(THREADPOOL_SIZE);

        int side = cube.getRandomSide();
        int oppositeSide = cube.getOppositeSide(side);
        int layer = cube.getRandomLayer();
        int oppositeLayer = cube.getOppositeLayer(layer);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(Executors.callable(new Rotator(side, layer)));
            tasks.add(Executors.callable(new Rotator(oppositeSide, oppositeLayer)));
        }

        rotatorPool.invokeAll(tasks);
        assert(cube.isSolved() && counterRotate.get() == 2 * THREADS);
    }

    /* Test sprawdzający, czy po ciągu tylu samych przeciwnych obrotów na danej warstwie
       w każdej płaszczyźnie kostka będzie ułożona. */
    @Test
    public void manyAntagonistRotationsTest() throws InterruptedException {
        int size = 10;
        AtomicInteger counterRotate = new AtomicInteger(0);
        cube = new Cube(size,
                (x, y) -> counterRotate.incrementAndGet(),
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        int THREADS = 10000;

        ExecutorService rotatorPool = Executors.newFixedThreadPool(THREADPOOL_SIZE);
        List<Callable<Object>> tasks = new ArrayList<>();

        int side = cube.getRandomSide();
        int oppositeSide = cube.getOppositeSide(side);
        for (int i = 0; i < THREADS; i++) {
            for (int layer = 0; layer < size; layer++) {
                tasks.add(Executors.callable(new Rotator(side, layer)));
                tasks.add(Executors.callable(new Rotator(oppositeSide, cube.getOppositeLayer(layer))));
            }
        }

        rotatorPool.invokeAll(tasks);
        assert(cube.isSolved() && counterRotate.get() == 2 * size * THREADS);
    }

    /* Stress test wykonujący bardzo dużo obrotów i sprawdzający czy na koniec stan kostki
       jest poprawny (liczba kwadratów w każdym kolorze wynosi size * size). */
    @Test
    public void stressTest() throws InterruptedException {
        int size = 100;
        AtomicInteger counterRotate = new AtomicInteger(0);
        cube = new Cube(size,
                (x, y) -> counterRotate.incrementAndGet(),
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        int THREADS = 1000000;

        ExecutorService rotatorPool = Executors.newFixedThreadPool(THREADPOOL_SIZE);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(Executors.callable(new Rotator(cube.getRandomSide(), cube.getRandomLayer())));
        }

        rotatorPool.invokeAll(tasks);
        assert(cube.hasCorrectNumberOfEachColor() && counterRotate.get() == THREADS);
    }

    /* Test sprawdzający, czy jeśli ciągle pracują procesy z danej grupy (bardzo dużo)
       i przyjdzie proces z innej grupy, to nie zostanie przez nie zagłodzony. */
    @Test
    public void starvationTest() throws InterruptedException {
        int size = 100;
        AtomicInteger counterRotate = new AtomicInteger(0);
        cube = new Cube(size,
                (x, y) -> counterRotate.incrementAndGet(),
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        int THREADS = 100000;

        ExecutorService rotatorPool = Executors.newFixedThreadPool(THREADPOOL_SIZE);
        List<Callable<Object>> tasks = new ArrayList<>();

        int meanSide = cube.getRandomSide();
        int meanOppositeSide = cube.getOppositeSide(meanSide);
        int poorSide = (meanSide + 1) % 6;

        for (int i = 0; i < THREADS; i++) {
            tasks.add(Executors.callable(new Rotator(meanSide, cube.getRandomLayer())));
            tasks.add(Executors.callable(new Rotator(meanOppositeSide, cube.getRandomLayer())));
            if (i == THREADS / 2) {
                tasks.add(Executors.callable(new Rotator(poorSide, cube.getRandomLayer())));
            }
        }

        rotatorPool.invokeAll(tasks);
        assert(cube.hasCorrectNumberOfEachColor() && counterRotate.get() == 2 * THREADS + 1);
    }

    /* Test przerywający wątek na semaforze dla reprezentantów grup. */
    @Test
    public void interruptProcessOnRepresentativesSemaphoreTest() throws InterruptedException {
        int size = 3;
        AtomicInteger counterRotate = new AtomicInteger(0);
        cube = new Cube(size,
                (x, y) -> {
                    counterRotate.incrementAndGet();
                    try {
                        sleep(30);
                    } catch (InterruptedException e) {
                        e.printStackTrace();

                    }
                },
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        List<Thread> threads = new ArrayList<>();
        threads.add(new Thread(new Rotator(3, 0)));
        threads.add(new Thread(new Rotator(2, 1)));
        threads.add(new Thread(new Rotator(0, 1)));

        /* Żeby test zadziałał wątek z obrotem (3, 0) musi pracować jako pierwszy -
           dlatego puszczamy go przed innymi i jest sleep. */
        threads.get(0).start();
        sleep(1);

        for (int i = 1; i < 3; i++) {
            threads.get(i).start();
        }

        threads.get(1).interrupt();

        for (Thread thread : threads) {
            thread.join();
        }

        String expected = "002002002111225111225333225333044333044111044554554554";
        assertEquals(expected, cube.show());
        assert(counterRotate.get() == 2);
    }

    /* Test przerywający wątek na semaforze dla warstw. */
    @Test
    public void interruptProcessOnLayerSemaphoreTest() throws InterruptedException {
        int size = 3;
        AtomicInteger counterRotate = new AtomicInteger(0);
        cube = new Cube(size,
                (x, y) -> {
                    counterRotate.incrementAndGet();
                    try {
                        sleep(30);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        List<Thread> threads = new ArrayList<>();
        threads.add(new Thread(new Rotator(3, 0)));
        threads.add(new Thread(new Rotator(3, 0)));
        threads.add(new Thread(new Rotator(0, 1)));

       /* Żeby test zadziałał wątek z obrotem (3, 0) musi pracować jako pierwszy -
           dlatego puszczamy go przed innymi i jest sleep. */
        threads.get(0).start();
        sleep(1);

        for (int i = 1; i < 3; i++) {
            threads.get(i).start();
        }

        threads.get(1).interrupt();

        for (Thread thread : threads) {
            thread.join();
        }

        String expected = "002002002111225111225333225333044333044111044554554554";
        assertEquals(expected, cube.show());
        assert(counterRotate.get() == 2);
    }

    /* Test przerywający wątek na semaforze dla grup. */
    @Test
    public void interruptProcessOnGroupSemaphoreTest() throws InterruptedException {
        int size = 3;
        AtomicInteger counterRotate = new AtomicInteger(0);
        cube = new Cube(size,
                (x, y) -> {
                    counterRotate.incrementAndGet();
                    try {
                        sleep(30);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                },
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        List<Thread> threads = new ArrayList<>();

        threads.add(new Thread(new Rotator(3, 0)));
        threads.add(new Thread(new Rotator(0, 1)));
        threads.add(new Thread(new Rotator(0, 2)));

        /* Żeby test zadziałał wątki muszą pracować w kolejności deklaracji -
           puszczamy je w kolejności sekwencyjnej ze sleepem. */
        threads.get(0).start();
        sleep(1);
        threads.get(1).start();
        sleep(1);
        threads.get(2).start();

        threads.get(2).interrupt();

        for (Thread thread : threads) {
            thread.join();
        }

        String expected = "002002002111225111225333225333044333044111044554554554";
        assertEquals(expected, cube.show());
        assert(counterRotate.get() == 2);
    }

    /* Test sprawdzający cykle - czy po 4 obrotach każdego rodzaju kostka jest ułożona. */
    @Test
    public void cycleTest() throws InterruptedException {
        int size = 10;
        cube = new Cube(size,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );
        for (int side = 0; side < SIDES; side++) {
            for (int layer = 0; layer < size; layer++) {
                for (int i = 0; i < 4; i++) {
                    cube.rotate(side, layer);
                }
            }
        }
        assertTrue(cube.isSolved());
    }

    /* Test sprawdzający poprawność wyniku sekwencyjnych obrotów dla kostki rozmiaru 3 -
       oczekiwany wynik wygenerowany za pomocą online scramble generatora. */
    @Test
    public void sequentialRotateCorrectnessTestSmall() throws InterruptedException {
        int size = 3;
        cube = new Cube(size,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );
        cube.rotate(1, 1);
        cube.rotate(1, 0);
        cube.rotate(5, 0);
        cube.rotate(2, 2);
        cube.rotate(0, 2);
        cube.rotate(0, 2);
        cube.rotate(2, 0);
        cube.rotate(3, 2);
        cube.rotate(3, 1);
        cube.rotate(1, 1);
        cube.rotate(1, 2);
        cube.rotate(4, 1);
        cube.rotate(5, 2);
        cube.rotate(5, 2);
        cube.rotate(1, 2);
        cube.rotate(0, 2);
        cube.rotate(3, 2);
        cube.rotate(0, 2);
        cube.rotate(4, 0);
        cube.rotate(1, 1);
        cube.rotate(1, 1);
        cube.rotate(1, 1);
        cube.rotate(3, 2);
        cube.rotate(4, 0);
        cube.rotate(1, 2);
        cube.rotate(0, 0);
        cube.rotate(4, 2);
        cube.rotate(4, 2);
        cube.rotate(2, 0);
        cube.rotate(1, 1);
        String expected = "021134322120543012235500155314424155504351333022014404";
        assertEquals(expected, cube.show());
    }

    /* Test sprawdzający poprawność wyniku sekwencyjnych obrotów dla kostki rozmiaru 5 -
       oczekiwany wynik wygenerowany za pomocą online scramble generatora. */
    @Test
    public void sequentialRotateCorrectnessTestMedium() throws InterruptedException {
        int size = 5;
        cube = new Cube(size,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );
        cube.rotate(1, 1);
        cube.rotate(2, 3);
        cube.rotate(1, 2);
        cube.rotate(3, 3);
        cube.rotate(2, 2);
        cube.rotate(1, 2);
        cube.rotate(4, 2);
        cube.rotate(1, 3);
        cube.rotate(1, 4);
        cube.rotate(0, 4);
        cube.rotate(4, 4);
        cube.rotate(2, 3);
        cube.rotate(3, 4);
        cube.rotate(5, 1);
        cube.rotate(3, 3);
        cube.rotate(5, 2);
        cube.rotate(3, 2);
        cube.rotate(0, 3);
        cube.rotate(1, 4);
        cube.rotate(1, 1);
        cube.rotate(4, 1);
        cube.rotate(1, 2);
        cube.rotate(3, 2);
        cube.rotate(2, 1);
        cube.rotate(1, 4);
        cube.rotate(1, 2);
        cube.rotate(0, 4);
        cube.rotate(5, 1);
        cube.rotate(5, 2);
        cube.rotate(4, 2);
        String expected = "005403523222501153132033545040124204344305542524251100121502503305132531332445523022414212452303511035243135403411020543101324544510033510021411512444";
        assertEquals(expected, cube.show());
    }

    /* Test sprawdzający poprawność wyniku sekwencyjnych obrotów dla kostki rozmiaru 7 -
       oczekiwany wynik wygenerowany za pomocą online scramble generatora. */
    @Test
    public void sequentialRotateCorrectnessTestBig() throws InterruptedException {
        int size = 7;
        cube = new Cube(size,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );
        cube.rotate(4, 4);
        cube.rotate(4, 0);
        cube.rotate(1, 0);
        cube.rotate(2, 3);
        cube.rotate(0, 4);
        cube.rotate(2, 0);
        cube.rotate(4, 3);
        cube.rotate(3, 2);
        cube.rotate(2, 6);
        cube.rotate(3, 1);
        cube.rotate(2, 5);
        cube.rotate(0, 4);
        cube.rotate(4, 4);
        cube.rotate(1, 1);
        cube.rotate(3, 4);
        cube.rotate(4, 0);
        cube.rotate(3, 1);
        cube.rotate(3, 4);
        cube.rotate(5, 1);
        cube.rotate(2, 1);
        cube.rotate(1, 5);
        cube.rotate(1, 6);
        cube.rotate(5, 0);
        cube.rotate(5, 6);
        cube.rotate(3, 1);
        cube.rotate(2, 4);
        cube.rotate(5, 2);
        cube.rotate(1, 1);
        cube.rotate(3, 5);
        cube.rotate(3, 6);
        String expected = "003442423225522131114210055135155214424415114455115552554255054522353341110542344044455511410111332200332110250040221001052230125234214113402511425010033340001303333253333322305034354440150211225051550243340303552444451040431001305321332341001240322003224223023445245355143521400311441045502253";
        assertEquals(expected, cube.show());
    }

    /* Malutki test na poprawność obrotu UP. */
    @Test
    public void smallRotateUpTest() throws InterruptedException {
        cube = new Cube(3,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        String expected = "000000000222111111333222222444333333111444444555555555";
        cube.rotate(0, 0);
        assertEquals(expected, cube.show());
    }

    /* Malutki test na poprawność obrotu LEFT. */
    @Test
    public void smallRotateLeftTest() throws InterruptedException {
        cube = new Cube(3,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        String expected = "400400400111111111022022022333333333445445445255255255";
        cube.rotate(1, 0);
        assertEquals(expected, cube.show());
    }

    /* Malutki test na poprawność obrotu FRONT. */
    @Test
    public void smallRotateFrontTest() throws InterruptedException {
        cube = new Cube(3,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        String expected = "000000111115115115222222222033033033444444444333555555";
        cube.rotate(2, 0);
        assertEquals(expected, cube.show());
    }

    /* Malutki test na poprawność obrotu RIGHT. */
    @Test
    public void smallRotateRightTest() throws InterruptedException {
        cube = new Cube(3,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        String expected = "002002002111111111225225225333333333044044044554554554";
        cube.rotate(3, 0);
        assertEquals(expected, cube.show());
    }

    /* Malutki test na poprawność obrotu BACK. */
    @Test
    public void smallRotateBackTest() throws InterruptedException {
        cube = new Cube(3,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        String expected = "333000000011011011222222222335335335444444444555555111";
        cube.rotate(4, 0);
        assertEquals(expected, cube.show());
    }

    /* Malutki test na poprawność obrotu DOWN. */
    @Test
    public void smallRotateDownTest() throws InterruptedException {
        cube = new Cube(3,
                (x, y) -> {},
                (x, y) -> {},
                () -> {},
                () -> {}
        );

        String expected = "000000000111111444222222111333333222444444333555555555";
        cube.rotate(5, 0);
        assertEquals(expected, cube.show());
    }

    /* Jedna rotacja. */
    private static class Rotation {

        private final int side;
        private final int layer;

        public Rotation(int side, int layer) {
            this.side = side;
            this.layer = layer;
        }
    }

    /* Stoper. */
    public static class Stopwatch {

        private Instant start;

        public void start() {
            start = Instant.now();
        }

        public Duration stop() {
            Duration duration = Duration.between(start, Instant.now());
            start = null;
            return duration;
        }
    }

    /* Funkcja pomocnicza generatora permutacji - sposób z WPI. */
    private static <T> void generatePermutationsHelper(int n, List<T> permutation, List<List<T>> permutations) {
        if (n == 1) {
            permutations.add(new ArrayList<>(permutation));
        } else {
            for (int i = 0; i < n; i++) {
                generatePermutationsHelper(n - 1, permutation, permutations);
                if (n % 2 == 0) {
                    Collections.swap(permutation, i, n - 1);
                } else {
                    Collections.swap(permutation, 0, n - 1);
                }
            }
        }
    }

    /* Generator permutacji danej listy. */
    private static <T> List<List<T>> generatePermutations(List<T> initialPermutation) {
        List<List<T>> permutations = new ArrayList<>();
        generatePermutationsHelper(initialPermutation.size(), initialPermutation, permutations);
        return permutations;
    }
}

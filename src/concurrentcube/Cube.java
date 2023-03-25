package concurrentcube;

import java.util.Arrays;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.concurrent.Semaphore;

public class Cube {
    private final int UP = 0;
    private final int LEFT = 1;
    private final int FRONT = 2;
    private final int RIGHT = 3;
    private final int BACK = 4;
    private final int DOWN = 5;
    private final int SIDES = 6;
    private final int ADJACENT_SIDES = 4;
    private final int GROUPS = 4;

    private final int size;
    private final BiConsumer<Integer, Integer> beforeRotation;
    private final BiConsumer<Integer, Integer> afterRotation;
    private final Runnable beforeShowing;
    private final Runnable afterShowing;
    private final int[][][] cube = new int[SIDES][][]; // Kolory kwadratów.
    private final int[] oppositeSide = new int[SIDES]; // Przeciwne ściany.
    private final int[][] adjacentSides = new int[SIDES][]; // Sąsiadujące ściany.

    /* Id pracującej grupy. */
    private int workingGroup = -1;

    /* Tablica semaforów do wieszania procesów z danych grup. */
    private final Semaphore[] groups = new Semaphore[GROUPS];

    /* Mutex - semafor binarny mutex realizujący wzajemne wykluczanie. */
    private final Semaphore mutex = new Semaphore(1, true);

    /* Liczba pracujących procesów. */
    private int numberOfRunningProcesses = 0;

    /* Semafor, na którym wieszają się reprezentanci danych grup - pierwsze procesy,
       które przyjdą. */
    private final Semaphore representatives = new Semaphore(0, true);

    /* Liczba czekających na wykonanie procesów z danej grupy. */
    private final int[] numberOfWaitingProcesses = new int[GROUPS];

    /* Liczba czekających reprezentantów. */
    private int numberOfWaitingGroups = 0;

    /* Tablica semaforów, na których wieszają się procesy z pracującej grupy robiącej obroty,
       gdy warstwa, na której chcą dokonać obrotu, jest zajęta przez inny proces. */
    private final Semaphore[] layers;

    /* Sztuczny limit na maksymalną liczbę procesów z grupy pracującej dopuszczonych do wykonania
       operacji w danej iteracji - wejścia do sekcji krytycznej. Na wypadek, gdyby procesy z
       pracującej obecnie grupy przychodziły w nieskończoność, a na wykonanie oczekiwał proces
       z innej grupy, przy ustawionym limicie na pewno nie dojdzie do zagłodzenia. */
    private final int groupLimit;

    /* Liczba procesów z grupy pracującej dopuszczonych do wykonania operacji w danej iteracji. */
    private int numberOfLetInProcesses = 0;

    public Cube(int size,
                BiConsumer<Integer, Integer> beforeRotation,
                BiConsumer<Integer, Integer> afterRotation,
                Runnable beforeShowing,
                Runnable afterShowing) {
        this.size = size;
        this.beforeRotation = beforeRotation;
        this.afterRotation = afterRotation;
        this.beforeShowing = beforeShowing;
        this.afterShowing = afterShowing;
        for (int side = 0; side < SIDES; side++) {
            this.cube[side] = new int[size][size];
            for (int row = 0; row < size; row++) {
                Arrays.fill(this.cube[side][row], side);
            }
        }
        for (int side = 0; side < SIDES; side++) {
            switch (side) {
                case UP :
                    oppositeSide[side] = DOWN;
                    adjacentSides[side] = new int[]{LEFT, FRONT, RIGHT, BACK};
                    break;
                case LEFT :
                    oppositeSide[side] = RIGHT;
                    adjacentSides[side] = new int[]{BACK, DOWN, FRONT, UP};
                    break;
                case FRONT:
                    oppositeSide[side] = BACK;
                    break;
                case RIGHT:
                    oppositeSide[side] = LEFT;
                    adjacentSides[side] = new int[]{BACK, UP, FRONT, DOWN};
                    break;
                case BACK:
                    oppositeSide[side] = FRONT;
                    break;
                case DOWN:
                    oppositeSide[side] = UP;
                    adjacentSides[side] = new int[]{RIGHT, FRONT, LEFT, BACK};
                    break;
            }
        }
        for (int i = 0; i < 4; i++) {
            this.groups[i] = new Semaphore(0);
        }
        Arrays.fill(numberOfWaitingProcesses, 0);
        this.layers = new Semaphore[size];
        for (int i = 0; i < size; i++) {
            this.layers[i] = new Semaphore(1);
        }
        this.groupLimit = 10 * size;
    }

    private int[][] getSide(int side) {
        return cube[side];
    }

    public int getOppositeSide(int side) {
        return oppositeSide[side];
    }

    private int[] getAdjacentSides(int side) {
        return adjacentSides[side];
    }

    public int getOppositeLayer(int layer) {
        return size - 1 - layer;
    }

    private boolean isTheFirstLayer(int layer) {
        return layer == 0;
    }

    private boolean isTheLastLayer(int layer) {
        return layer == size - 1;
    }

    /* Funkcja obracająca zgodnie z ruchem wskazówek zegara daną ścianę. */
    private void rotateSideClockwise(int side) {
        int[][] cubeSide = getSide(side);
        for (int i = 0; i < size / 2; i++) {
            for (int j = i; j < size - i - 1; j++) {
                int temp = cubeSide[i][j];
                cubeSide[i][j] = cubeSide[size - j - 1][i];
                cubeSide[size - j - 1][i] = cubeSide[size - i - 1][size - j - 1];
                cubeSide[size - i - 1][size - j - 1] = cubeSide[j][size - i - 1];
                cubeSide[j][size - i - 1] = temp;
            }
        }
    }

    /* Funkcja obracająca przeciwnie do ruchu wskazówek zegara daną ścianę. */
    private void rotateSideCounterclockwise(int side) {
        int[][] cubeSide = getSide(side);
        for (int i = 0; i < size / 2; i++) {
            for (int j = i; j < size - i - 1; j++) {
                int temp = cubeSide[i][j];
                cubeSide[i][j] = cubeSide[j][size - i - 1];
                cubeSide[j][size - i - 1] = cubeSide[size - i - 1][size - i - 1 - (j - i)];
                cubeSide[size - i - 1][size - i - 1 - (j - i)] = cubeSide[size - i - 1 - (j - i)][i];
                cubeSide[size - i - 1 - (j - i)][i] = temp;
            }
        }
    }

    /* Funkcja wywoływana przy obrotach w płaszczyźnie UP/DOWN. */
    private void rotateRows(int side, int layer) {
        int[] neighbours = getAdjacentSides(side);
        int row = -1;
        switch (side) {
            case UP:
                row = layer;
                break;
            case DOWN:
                row = getOppositeLayer(layer);
                break;
        }
        for (int column = 0; column < size; column++) {
            int temp = cube[neighbours[0]][row][column];
            for (int i = 0; i < ADJACENT_SIDES - 1; i++) {
                cube[neighbours[i]][row][column] = cube[neighbours[i + 1]][row][column];
            }
            cube[neighbours[ADJACENT_SIDES - 1]][row][column] = temp;
        }
    }

    /* Funkcja wywoływana przy obrotach w płaszczyźnie LEFT/RIGHT. */
    private void rotateColumns(int side, int layer) {
        int[] neighbours = getAdjacentSides(side);
        int column = -1;
        switch (side) {
            case LEFT:
                column = layer;
                break;
            case RIGHT:
                column = getOppositeLayer(layer);
                break;
        }
        int oppositeColumn = getOppositeLayer(column);
        for (int row = 0; row < size; row++) {
            /* Pierwszą sąsiadującą ścianą jest BACK, więc musimy wyodrębnić
               operacje związane z nią (z powodu nieco innej numeracji). */
            int temp = cube[neighbours[0]][size - row - 1][oppositeColumn];
            cube[neighbours[0]][size - row - 1][oppositeColumn] = cube[neighbours[1]][row][column];
            for (int i = 1; i < ADJACENT_SIDES - 1; i++) {
                cube[neighbours[i]][row][column] = cube[neighbours[i + 1]][row][column];
            }
            cube[neighbours[ADJACENT_SIDES - 1]][row][column] = temp;
        }
    }

    /* Funkcja wywoływana przy obrotach w płaszczyźnie FRONT/BACK. */
    private void rotateRowsAndColumns(int side, int layer) {
        int oppositeLayer = getOppositeLayer(layer);
        switch (side) {
            case FRONT:
                for (int i = 0; i < size; i++) {
                    int temp = cube[UP][oppositeLayer][i];
                    cube[UP][oppositeLayer][i] = cube[LEFT][size - i - 1][oppositeLayer];
                    cube[LEFT][size - i - 1][oppositeLayer] = cube[DOWN][layer][size - i - 1];
                    cube[DOWN][layer][size - i - 1] = cube[RIGHT][i][layer];
                    cube[RIGHT][i][layer] = temp;
                }
                break;
            case BACK:
                for (int i = 0; i < size; i++) {
                    int temp = cube[DOWN][oppositeLayer][i];
                    cube[DOWN][oppositeLayer][i] = cube[LEFT][i][layer];
                    cube[LEFT][i][layer] = cube[UP][layer][size - i - 1];
                    cube[UP][layer][size - i - 1] = cube[RIGHT][size - i - 1][oppositeLayer];
                    cube[RIGHT][size - i - 1][oppositeLayer] = temp;
                }
                break;
        }
    }

    /* Zwraca id grupy procesów wykonujących obroty:
       0 dla płaszczyzny UP/DOWN,
       1 dla płaszczyzny LEFT/RIGHT,
       2 dla płaszczyzny FRONT/BACK. */
    private int getGroupId(int side) {
        // Id grupy wykonującej obroty w płaszczyźnie UP/DOWN.
        int ROTATE_UP_AND_DOWN = 0;
        // Id grupy wykonującej obroty w płaszczyźnie LEFT/RIGHT.
        int ROTATE_LEFT_AND_RIGHT = 1;
        // Id grupy wykonującej obroty w płaszczyźnie FRONT/BACK.
        int ROTATE_FRONT_AND_BACK = 2;
        switch (side) {
            case UP:
            case DOWN:
                return ROTATE_UP_AND_DOWN;
            case LEFT:
            case RIGHT:
                return ROTATE_LEFT_AND_RIGHT;
            case FRONT:
            case BACK:
                return ROTATE_FRONT_AND_BACK;
            default:
                throw new IllegalStateException("Unexpected value: " + side);
        }
    }

    /* Warstwy są liczone kolejno:
       - w płaszczyźnie UP/DOWN -> od UP,
       - w płaszczyźnie LEFT/RIGHT -> od LEFT,
       - w płaszczyźnie FRONT/BACK -> od FRONT. */
    private int getLayerId(int side, int layer) {
        switch (side) {
            case UP:
            case LEFT:
            case FRONT:
                return layer;
            case RIGHT:
            case BACK:
            case DOWN:
                return size - layer - 1;
            default:
                throw new IllegalStateException("Unexpected value: " + side);
        }
    }

    /* Funkcja czekająca w protokole wstępnym. */
    private void wait(int groupId) throws InterruptedException {
        numberOfWaitingProcesses[groupId]++;
        if (numberOfWaitingProcesses[groupId] == 1) {
            /* Jesteśmy pierwszym procesem z naszej grupy - zostajemy reprezentantem. */
            numberOfWaitingGroups++;
            /* Oddajemy mutexa. */
            mutex.release();
            /* Próbujemy zawiesić się na semaforze dla reprezentantów - jeśli wątek
               zostanie przerwany cofamy dotychczasowe zmiany używając mutexa. */
            try {
                representatives.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mutex.acquireUninterruptibly();
                numberOfWaitingGroups--;
                numberOfWaitingProcesses[groupId]--;
                mutex.release();
                throw e;
            }
            /* Odziedziczyliśmy mutexa - oznajmiamy, że nasza grupa będzie pracować. */
            workingGroup = groupId;
            numberOfWaitingGroups--;
        } else {
            /* Oddajemy mutexa. */
            mutex.release();
            /* Próbujemy zawiesić się na semaforze dla grup - jeśli wątek
               zostanie przerwany cofamy dotychczasowe zmiany używając mutexa. */
            try {
                groups[groupId].acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mutex.acquireUninterruptibly();
                numberOfWaitingProcesses[groupId]--;
                mutex.release();
                throw e;
            }
            /* Odziedziczyliśmy mutexa. */
        }
        /* Posiadamy mutex - możemy zaktualizować wartości zmiennych. */
        numberOfWaitingProcesses[groupId]--;
        numberOfRunningProcesses++;
        /* Kaskadowe budzenie innych procesów z naszej grupy - jeśli nie osiągneliśmy
           limitu wpuszczonych procesów lub żadna inna grupa nie czeka. */
        if (numberOfWaitingProcesses[groupId] > 0
            && (numberOfLetInProcesses < groupLimit || numberOfWaitingGroups == 0)) {
                groups[groupId].release();
        } else {
            mutex.release();
        }
    }

    /* Protokół wstępny przed wykonaniem operacji. */
    private void preProtocol(int groupId) throws InterruptedException {
        /* Wieszamy się na mutexie. */
        mutex.acquireUninterruptibly();
        if (workingGroup != -1 && workingGroup != groupId) {
            /* Inna grupa obecnie pracuje - wchodzimy do funkcji czekającej
               posiadając mutexa. */
            wait(groupId);
        } else {
            if (workingGroup == groupId) {
                /* Nasza grupa pracuje. */
                if (numberOfLetInProcesses < groupLimit || numberOfWaitingGroups == 0) {
                    /* Nie przekroczyliśmy limitu lub nie ma innych czekających grup. */
                    numberOfLetInProcesses++;
                    numberOfRunningProcesses++;
                    /* Oddajemy mutexa. */
                    mutex.release();
                } else {
                    /* Osiągneliśmy limit - proces musi poczekać - wchodzimy do funkcji
                       czekającej posiadając mutexa. */
                    wait(groupId);
                }
            } else {
                /* Nikt nie pracuje - możemy rozpocząć pracę. */
                numberOfLetInProcesses++;
                workingGroup = groupId;
                numberOfRunningProcesses++;
                /* Oddajemy mutexa. */
                mutex.release();
            }
        }
    }

    private void postProtocol() {
        /* Wieszamy się na mutexie. */
        mutex.acquireUninterruptibly();
        numberOfRunningProcesses--;
        if (numberOfRunningProcesses == 0) {
            /* Wszyscy skończyli pracę. */
            numberOfLetInProcesses = 0;
            if (numberOfWaitingGroups > 0) {
                /* Jeśli jakaś grupa czeka, budzimy jednego z reprezentantów,
                   przekazując mu mutex. */
                representatives.release();
            } else {
                /* Nie ma czekających grup. */
                workingGroup = -1;
                /* Oddajemy mutexa. */
                mutex.release();
            }
        } else {
            /* Jakieś procesy nadal pracują - oddajemy mutexa. */
            mutex.release();
        }
    }

    /* Właściwa funkcja dokonująca obrotu na kostce. */
    private void performARotation(int side, int layer) {
        if (isTheFirstLayer(layer)) {
            rotateSideClockwise(side);
        }
        if (isTheLastLayer(layer)) {
            rotateSideCounterclockwise(oppositeSide[side]);
        }
        switch (side) {
            case UP:
            case DOWN:
                rotateRows(side, layer);
                break;
            case LEFT:
            case RIGHT:
                rotateColumns(side, layer);
                break;
            case FRONT:
            case BACK:
                rotateRowsAndColumns(side, layer);
                break;
        }
    }

    public void rotate(int side, int layer) throws InterruptedException {
        int layerId = getLayerId(side, layer);
        preProtocol(getGroupId(side));
        /* Próbujemy zawiesić się na semaforze dla wartstwa - jeśli wątek
           zostanie przerwany wykonujemy protokół końcowy. */
        try {
            layers[layerId].acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            postProtocol();
            throw e;
        }
        beforeRotation.accept(side, layer);
        performARotation(side, layer);
        afterRotation.accept(side, layer);
        layers[layerId].release();
        postProtocol();
    }

    /* Właściwa funkcja zwracająca obecny stan kostki. */
    private String performAShow() {
        StringBuilder cubeState = new StringBuilder();
        for (int side = 0; side < SIDES; side++) {
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    cubeState.append(cube[side][row][column]);
                }
            }
        }
        return cubeState.toString();
    }

    public String show() throws InterruptedException {
        // Id grupy wykonującej pokazywanie kostki.
        int SHOW = 3;
        preProtocol(SHOW);
        beforeShowing.run();
        String cubeState = performAShow();
        afterShowing.run();
        postProtocol();
        return cubeState;
    }

    /* Funkcja resetująca kostkę do wersji ułożonej. */
    public void reset() {
        for (int side = 0; side < SIDES; side++) {
            this.cube[side] = new int[size][size];
            for (int row = 0; row < size; row++) {
                Arrays.fill(this.cube[side][row], side);
            }
        }
    }

    public int getRandomSide() {
        Random random = new Random();
        return random.nextInt(SIDES);
    }

    public int getRandomLayer() {
        Random random = new Random();
        return random.nextInt(size);
    }

    /* Sprawdza, czy kostka jest ułożona. */
    public boolean isSolved() {
        for (int side = 0; side < SIDES; side++) {
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    if (cube[side][row][column] != side) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /* Sprawdza, czy kostka posiada (size * size) kwadratów każdego koloru. */
    public boolean hasCorrectNumberOfEachColor() {
        int[] counters = new int[SIDES];
        Arrays.fill(counters, 0);
        for (int side = 0; side < SIDES; side++) {
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    counters[cube[side][row][column]]++;
                }
            }
        }
        for (int side = 0; side < SIDES; side++) {
            if (counters[side] != size * size) {
                return false;
            }
        }
        return true;
    }
}
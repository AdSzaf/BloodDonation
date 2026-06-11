package BloodPoints;

import java.util.Random;

public class BloodPoints {
    int timeToNext;
    private Random random;

    public BloodPoints() {
        random = new Random();
        timeToNext = generateTimeToNext();
    }

    public int produce()
    {
        timeToNext=generateTimeToNext();
        int count = random.nextInt(4)+1;
        System.out.println("I produced " + count + ". Next I'll produce in " + timeToNext);
        return count;
    }

    public int getTimeToNext() {
        return timeToNext;
    }

    private int generateTimeToNext()
    {
        return random.nextInt(10)+1;
    }
}

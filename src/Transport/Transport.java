package Transport;

public class Transport {
    private int available;
    private int max;
    private static Transport instance = null;

    private Transport() {
        available=0;
        max=20;

    }

    static public Transport getInstance()
    {
        if(instance==null) instance = new Transport();
        return instance;
    }

    public int getAvailable() {
        return available;
    }

    public void setAvailable(int available) {
        this.available = available;
    }

    public int getMax() {
        return max;
    }

    public void setMax(int max) {
        this.max = max;
    }

    public boolean addTo(int count)
    {
        if(this.available+count<=this.max) {
            this.available += count;
            System.out.println("Storage: I just got for " + count + ". Now I have " + this.available + " products");
            return true;
        }
        else
        {
            System.out.println("Storage: I have no left space for " + count + " products");
            return false;
        }
    }

    public boolean getFrom(int count)
    {
        if(available-count>=0) {
            this.available-=count;
            System.out.println("Storage: I just given " + count + ". Now I have " + this.available + " products");
            return true;
        }
        else
        {
            System.out.println("Storage: I have no left products to give");
            return false;
        }
    }
}

package Transport;

import hla.rti1516e.*;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.exceptions.*;
import hla.rti1516e.time.HLAfloat64Interval;
import hla.rti1516e.time.HLAfloat64Time;
import hla.rti1516e.time.HLAfloat64TimeFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * Federat Transport - Centrum Logistyczno-Magazynowe RCKiK.
 *
 * Subskrybuje: BloodCollected, Request
 * Publikuje:   BloodTransported
 *
 * Cala logika magazynu (FIFO, utylizacja, wydawanie) jest w singletonie
 * Transport.getInstance(). Ten federat odpowiada wylacznie za:
 *  - glowna petle symulacyjna (advanceTime co 1 j.s.)
 *  - wywolanie Transport.removeExpired() co krok
 *  - realizacje opoznionych dostaw przez PendingDelivery
 *  - wysylanie interakcji BloodTransported przez RTI
 */
public class TransportFederate {

	public static final String READY_TO_RUN   = "ReadyToRun";
	private static final String FEDERATION_NAME = "BloodSupplyFederation";
	private static final String FOM_PATH        = "foms/ProducerConsumer.xml";

	// Losowy czas transportu do szpitala: 1-5 jednostek symulacyjnych
	private static final int MIN_TRANSPORT_TIME = 1;
	private static final int MAX_TRANSPORT_TIME = 5;

	// RTI
	private RTIambassador rtiamb;
	private TransportFederateAmbassador fedamb;
	private HLAfloat64TimeFactory timeFactory;
	protected EncoderFactory encoderFactory;

	// --- Uchwyty BloodCollected (subskrypcja) ---
	protected InteractionClassHandle bloodCollectedHandle;
	protected ParameterHandle bcBloodIdHandle;
	protected ParameterHandle bcBloodAmountHandle;
	protected ParameterHandle bcBloodTypeHandle;
	protected ParameterHandle bcDonationTimeHandle;
	protected ParameterHandle bcIsMobileHandle;

	// --- Uchwyty Request (subskrypcja) ---
	protected InteractionClassHandle requestHandle;
	protected ParameterHandle reqRequestIdHandle;
	protected ParameterHandle reqHospitalIdHandle;
	protected ParameterHandle reqBloodTypeHandle;
	protected ParameterHandle reqRequestedAmountHandle;
	protected ParameterHandle reqIsUrgentHandle;

	// --- Uchwyty BloodTransported (publikacja) ---
	protected InteractionClassHandle bloodTransportedHandle;
	protected ParameterHandle btBloodIdHandle;
	protected ParameterHandle btHospitalIdHandle;
	protected ParameterHandle btBloodAmountHandle;
	protected ParameterHandle btBloodTypeHandle;
	protected ParameterHandle btDonationTimeHandle;

	// Kolejka dostaw oczekujacych na uplyniecie czasu transportu
	private final Queue<PendingDelivery> pendingDeliveries = new LinkedList<>();
	private final Random random = new Random();

	// -----------------------------------------------------------------------

	private void log(String message) {
		System.out.println("TransportFederate  : " + message);
	}

	private void waitForUser() {
		log(">>>>>>>>>> Nacisnij Enter aby kontynuowac <<<<<<<<<<");
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		try { reader.readLine(); }
		catch (Exception e) { log("Blad: " + e.getMessage()); }
	}

	// -----------------------------------------------------------------------
	// Glowna metoda symulacji
	// -----------------------------------------------------------------------
	public void runFederate(String federateName) throws Exception {

		log("Tworze RTIambassador...");
		rtiamb = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
		encoderFactory = RtiFactoryFactory.getRtiFactory().getEncoderFactory();
		fedamb = new TransportFederateAmbassador(this);
		rtiamb.connect(fedamb, CallbackModel.HLA_EVOKED);

		log("Tworze/dolaczam do federacji...");
		try {
			URL[] modules = { (new File(FOM_PATH)).toURI().toURL() };
			rtiamb.createFederationExecution(FEDERATION_NAME, modules);
			log("Federacja utworzona.");
		} catch (FederationExecutionAlreadyExists e) {
			log("Federacja juz istnieje - dolaczam.");
		} catch (MalformedURLException e) {
			log("Blad URL FOM: " + e.getMessage()); return;
		}

		rtiamb.joinFederationExecution(federateName, "transport", FEDERATION_NAME);
		log("Dolaczono jako " + federateName);
		this.timeFactory = (HLAfloat64TimeFactory) rtiamb.getTimeFactory();

		rtiamb.registerFederationSynchronizationPoint(READY_TO_RUN, null);
		while (!fedamb.isAnnounced) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
		waitForUser();
		rtiamb.synchronizationPointAchieved(READY_TO_RUN);
		while (!fedamb.isReadyToRun) rtiamb.evokeMultipleCallbacks(0.1, 0.2);

		enableTimePolicy();
		log("Polityka czasu wlaczona.");
		publishAndSubscribe();
		log("Publikowanie i subskrypcja skonfigurowane.");

		// -----------------------------------------------------------------------
		// Glowna petla: krok co 1 jednostke symulacyjna
		// -----------------------------------------------------------------------
		Transport storage = Transport.getInstance();

		while (fedamb.isRunning) {
			advanceTime(1.0);
			double currentTime = fedamb.federateTime;

			// 1. Utylizacja przeterminowanej krwi - deleguj do singletona
			int expired = storage.removeExpired(currentTime);
			if (expired > 0)
				log("Usunieto " + expired + " przeterminowanych jednostek.");

			// 2. Realizacja dostaw, ktorych czas transportu uplynal
			processPendingDeliveries(currentTime);

			log("t=" + currentTime
					+ " | Magazyn: " + storage.getTotalUnits() + " szt. ["
					+ storage.getStorageSummary() + "]"
					+ " | Niedobory: " + storage.getShortageCount());
		}

		rtiamb.resignFederationExecution(ResignAction.DELETE_OBJECTS);
		log("Zrezygnowano. Laczna liczba niedoborow: " + storage.getShortageCount());
		try {
			rtiamb.destroyFederationExecution(FEDERATION_NAME);
		} catch (FederationExecutionDoesNotExist | FederatesCurrentlyJoined e) {
			log("Nie mozna zniszczyc federacji: " + e.getMessage());
		}
	}

	// -----------------------------------------------------------------------
	// Obsluga zapotrzebowania od szpitala
	// Wywolywana z TransportFederateAmbassador po odebraniu Request
	// -----------------------------------------------------------------------
	void handleRequest(int requestId, int hospitalId, String bloodType,
					   float requestedAmount, boolean isUrgent) {

		log("Odebrano Request #" + requestId
				+ " | szpital=" + hospitalId
				+ " | typ=" + bloodType
				+ " | ilosc=" + requestedAmount
				+ " | nagly=" + isUrgent);

		// Deleguj do singletona - FIFO, zwraca null jesli niedobor
		Transport.BloodEntry unit =
				Transport.getInstance().fulfillRequest(bloodType, requestedAmount);

		if (unit == null) {
			// Niedobor juz zalogowany i policzony wewnatrz Transport.fulfillRequest()
			return;
		}

		// Zaplanuj dostawe po losowym czasie transportu
		int transportDelay = random.nextInt(MAX_TRANSPORT_TIME - MIN_TRANSPORT_TIME + 1)
				+ MIN_TRANSPORT_TIME;
		double deliveryTime = fedamb.federateTime + transportDelay;

		pendingDeliveries.add(new PendingDelivery(
				unit.bloodId, hospitalId,
				unit.bloodAmount, unit.bloodType,
				unit.donationTime, deliveryTime
		));

		log("Zaplanowano transport: id=" + unit.bloodId
				+ " -> szpital=" + hospitalId
				+ " (za " + transportDelay + " j.s., o t=" + deliveryTime + ")");
	}

	// -----------------------------------------------------------------------
	// Sprawdza kolejke i wysyla BloodTransported gdy czas transportu uplynal
	// -----------------------------------------------------------------------
	private void processPendingDeliveries(double currentTime) throws RTIexception {
		Iterator<PendingDelivery> it = pendingDeliveries.iterator();
		while (it.hasNext()) {
			PendingDelivery d = it.next();
			if (currentTime >= d.deliveryTime) {
				sendBloodTransported(d);
				it.remove();
			}
		}
	}

	// -----------------------------------------------------------------------
	// Wyslanie interakcji BloodTransported
	// -----------------------------------------------------------------------
	private void sendBloodTransported(PendingDelivery d) throws RTIexception {
		ParameterHandleValueMap params = rtiamb.getParameterHandleValueMapFactory().create(5);

		params.put(btBloodIdHandle,
				encoderFactory.createHLAinteger32BE(d.bloodId).toByteArray());
		params.put(btHospitalIdHandle,
				encoderFactory.createHLAinteger32BE(d.hospitalId).toByteArray());
		params.put(btBloodAmountHandle,
				encoderFactory.createHLAfloat32BE(d.bloodAmount).toByteArray());
		params.put(btBloodTypeHandle,
				encoderFactory.createHLAASCIIstring(d.bloodType).toByteArray());
		params.put(btDonationTimeHandle,
				encoderFactory.createHLAfloat64BE(d.donationTime).toByteArray());

		rtiamb.sendInteraction(bloodTransportedHandle, params, generateTag());
		log("Wyslano BloodTransported: id=" + d.bloodId
				+ " -> szpital=" + d.hospitalId
				+ " typ=" + d.bloodType
				+ " ilosc=" + d.bloodAmount);
	}

	// -----------------------------------------------------------------------
	// Metody pomocnicze HLA
	// -----------------------------------------------------------------------
	private void enableTimePolicy() throws Exception {
		HLAfloat64Interval lookahead = timeFactory.makeInterval(fedamb.federateLookahead);
		rtiamb.enableTimeRegulation(lookahead);
		while (!fedamb.isRegulating) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
		rtiamb.enableTimeConstrained();
		while (!fedamb.isConstrained) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
	}

	private void publishAndSubscribe() throws RTIexception {
		// Subskrybuj BloodCollected
		bloodCollectedHandle  = rtiamb.getInteractionClassHandle("HLAinteractionRoot.BloodCollected");
		bcBloodIdHandle       = rtiamb.getParameterHandle(bloodCollectedHandle, "bloodId");
		bcBloodAmountHandle   = rtiamb.getParameterHandle(bloodCollectedHandle, "bloodAmount");
		bcBloodTypeHandle     = rtiamb.getParameterHandle(bloodCollectedHandle, "bloodType");
		bcDonationTimeHandle  = rtiamb.getParameterHandle(bloodCollectedHandle, "donationTime");
		bcIsMobileHandle      = rtiamb.getParameterHandle(bloodCollectedHandle, "isMobile");
		rtiamb.subscribeInteractionClass(bloodCollectedHandle);
		log("Subskrybuje: BloodCollected");

		// Subskrybuj Request
		requestHandle            = rtiamb.getInteractionClassHandle("HLAinteractionRoot.Request");
		reqRequestIdHandle       = rtiamb.getParameterHandle(requestHandle, "requestId");
		reqHospitalIdHandle      = rtiamb.getParameterHandle(requestHandle, "hospitalId");
		reqBloodTypeHandle       = rtiamb.getParameterHandle(requestHandle, "bloodType");
		reqRequestedAmountHandle = rtiamb.getParameterHandle(requestHandle, "requestedAmount");
		reqIsUrgentHandle        = rtiamb.getParameterHandle(requestHandle, "isUrgent");
		rtiamb.subscribeInteractionClass(requestHandle);
		log("Subskrybuje: Request");

		// Publikuj BloodTransported
		bloodTransportedHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.BloodTransported");
		btBloodIdHandle        = rtiamb.getParameterHandle(bloodTransportedHandle, "bloodId");
		btHospitalIdHandle     = rtiamb.getParameterHandle(bloodTransportedHandle, "hospitalId");
		btBloodAmountHandle    = rtiamb.getParameterHandle(bloodTransportedHandle, "bloodAmount");
		btBloodTypeHandle      = rtiamb.getParameterHandle(bloodTransportedHandle, "bloodType");
		btDonationTimeHandle   = rtiamb.getParameterHandle(bloodTransportedHandle, "donationTime");
		rtiamb.publishInteractionClass(bloodTransportedHandle);
		log("Publikuje: BloodTransported");
	}

	private void advanceTime(double timestep) throws RTIexception {
		fedamb.isAdvancing = true;
		HLAfloat64Time time = timeFactory.makeTime(fedamb.federateTime + timestep);
		rtiamb.timeAdvanceRequest(time);
		while (fedamb.isAdvancing) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
	}

	private byte[] generateTag() {
		return ("(ts)" + System.currentTimeMillis()).getBytes();
	}

	// -----------------------------------------------------------------------
	// Klasa pomocnicza: oczekujaca dostawa (dane potrzebne do wyslania BloodTransported)
	// Nie zastepuje Transport.BloodEntry - sluzy tylko do kolejkowania dostaw w locie
	// -----------------------------------------------------------------------
	static class PendingDelivery {
		final int    bloodId;
		final int    hospitalId;
		final float  bloodAmount;
		final String bloodType;
		final double donationTime;
		final double deliveryTime;

		PendingDelivery(int bloodId, int hospitalId, float bloodAmount,
						String bloodType, double donationTime, double deliveryTime) {
			this.bloodId      = bloodId;
			this.hospitalId   = hospitalId;
			this.bloodAmount  = bloodAmount;
			this.bloodType    = bloodType;
			this.donationTime = donationTime;
			this.deliveryTime = deliveryTime;
		}
	}

	// -----------------------------------------------------------------------
	public static void main(String[] args) {
		String federateName = (args.length > 0) ? args[0] : "Transport";
		try {
			new TransportFederate().runFederate(federateName);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
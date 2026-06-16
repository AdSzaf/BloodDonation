package BloodPoints;

import core.Main;
import core.SimulationStats;
import hla.rti1516e.*;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.encoding.HLAASCIIstring;
import hla.rti1516e.encoding.HLAfloat32BE;
import hla.rti1516e.encoding.HLAfloat64BE;
import hla.rti1516e.encoding.HLAinteger32BE;
import hla.rti1516e.exceptions.*;
import hla.rti1516e.time.HLAfloat64Interval;
import hla.rti1516e.time.HLAfloat64Time;
import hla.rti1516e.time.HLAfloat64TimeFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * Federat BloodPoints - symuluje punkty poboru krwi (stacjonarne i mobilne).
 *
 * Publikuje:  BloodCollected
 * Subskrybuje: (nic)
 *
 * Logika glownej petli:
 *  1. Losuj czas przyjscia dawcy i odczekaj.
 *  2. Dawca przechodzi badania laboratoryjne (losowy czas).
 *  3. Jesli dawca sie kwalifikuje (80% szans):
 *     - Stacjonarny: wyslij BloodCollected natychmiast.
 *     - Mobilny: dodaj do bufora (z bloodType i donationTime);
 *                po zakonczeniu zmiany wyslij serie BloodCollected
 *                z oryginalnym typem krwi i czasem pobrania.
 */
public class BloodPointsFederate {

	public static final String READY_TO_RUN = "ReadyToRun";
	private static final String FEDERATION_NAME = "BloodSupplyFederation";
	private static final String FOM_PATH = "foms/ProducerConsumer.xml";
	private String federateName;

	// RTI
	private RTIambassador rtiamb;
	private BloodPointsFederateAmbassador fedamb;
	private HLAfloat64TimeFactory timeFactory;
	protected EncoderFactory encoderFactory;

	// Uchwyty interakcji BloodCollected i jej parametrow
	protected InteractionClassHandle bloodCollectedHandle;
	protected ParameterHandle bcBloodIdHandle;
	protected ParameterHandle bcBloodAmountHandle;
	protected ParameterHandle bcBloodTypeHandle;
	protected ParameterHandle bcDonationTimeHandle;
	protected ParameterHandle bcIsMobileHandle;

	// -----------------------------------------------------------------------

	private void log(String message) {
		System.out.println("[" + federateName + "] : " + message);
	}

	private void waitForUser() {
		log(">>>>>>>>>> Nacisnij Enter aby kontynuowac <<<<<<<<<<");
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		try { reader.readLine(); }
		catch (Exception e) { log("Blad oczekiwania na uzytkownika: " + e.getMessage()); }
	}

	// -----------------------------------------------------------------------
	// Glowna metoda symulacji
	// -----------------------------------------------------------------------
	public void runFederate(String federateName) throws Exception {
		this.federateName = federateName;

		// 1. Utworz RTIambassador i polacz
		log("Tworze RTIambassador...");
		rtiamb = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
		encoderFactory = RtiFactoryFactory.getRtiFactory().getEncoderFactory();
		fedamb = new BloodPointsFederateAmbassador(this);
		rtiamb.connect(fedamb, CallbackModel.HLA_EVOKED);

		// 2. Stworz lub dolacz do federacji
		log("Tworze federacje...");
		try {
			URL[] modules = { (new File(FOM_PATH)).toURI().toURL() };
			rtiamb.createFederationExecution(FEDERATION_NAME, modules);
			log("Federacja utworzona.");
		} catch (FederationExecutionAlreadyExists e) {
			log("Federacja juz istnieje - dolaczam.");
		} catch (MalformedURLException e) {
			log("Blad URL FOM: " + e.getMessage());
			return;
		}

		// 3. Dolacz do federacji
		rtiamb.joinFederationExecution(federateName, "bloodpoints", FEDERATION_NAME);
		log("Dolaczono do federacji jako " + federateName);
		this.timeFactory = (HLAfloat64TimeFactory) rtiamb.getTimeFactory();

		// 4. Synchronizacja ReadyToRun
		//rtiamb.registerFederationSynchronizationPoint(READY_TO_RUN, null);
		while (!fedamb.isAnnounced) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
		while (!Main.startSimulation) {
			Thread.sleep(100);
		}
		rtiamb.synchronizationPointAchieved(READY_TO_RUN);
		log("Osiagnieto punkt synchronizacji: " + READY_TO_RUN);
		while (!fedamb.isReadyToRun) rtiamb.evokeMultipleCallbacks(0.1, 0.2);

		// 5. Polityki czasu
		enableTimePolicy();
		log("Polityka czasu wlaczona.");

		// 6. Publikuj i subskrybuj
		publishAndSubscribe();
		log("Publikowanie i subskrypcja skonfigurowane.");

		// -----------------------------------------------------------------------
		// Glowna petla symulacji
		// -----------------------------------------------------------------------
		BloodPoints bp = new BloodPoints();
		int donorCounter = 0;
		int shiftElapsed = 0; // czas uplyniety w biezacej zmianie (dla krwiobusu)

		while (fedamb.isRunning && fedamb.federateTime < Main.SIMULATION_END_TIME) {

			// --- Krok 1: czas przyjscia kolejnego dawcy ---
			int timeToNextDonor = bp.getTimeToNextDonor();
			advanceTime(timeToNextDonor);
			shiftElapsed += timeToNextDonor;
			donorCounter++;
			log("Pojawil sie dawca #" + donorCounter + " (t=" + fedamb.federateTime + ")");

			// --- Krok 2: badania laboratoryjne ---
			int labTime = bp.getLabTestDuration();
			advanceTime(labTime);
			shiftElapsed += labTime;
			log("Dawca #" + donorCounter + " przeszedl badania (czas lab=" + labTime + ")");

			// --- Krok 3: kwalifikacja dawcy ---
			if (!bp.donorQualifies()) {
				log("Dawca #" + donorCounter + " NIE przeszedl kwalifikacji - odrzucony.");
			} else {
				int bloodId = bp.nextBloodId();
				String bloodType = bp.randomBloodType();
				float amount = BloodPoints.UNIT_VOLUME;
				double donationTime = fedamb.federateTime; // czas rzeczywistego pobrania

				if (!bp.isMobile()) {
					// --- STACJONARNY: wyslij natychmiast ---
					log("Dawca #" + donorCounter + " [STACJONARNY] - wysylam BloodCollected #"
							+ bloodId + " (" + bloodType + ")");
					sendBloodCollected(bloodId, amount, bloodType, donationTime, false);
				} else {
					// --- MOBILNY: buforuj z oryginalnym typem krwi i czasem pobrania ---
					bp.addToMobileBuffer(bloodId, bloodType, donationTime);
				}
			}

			// --- Krok 4: dla krwiobusu - czy zmiana sie skonczyla? ---
			if (bp.isMobile() && bp.isShiftOver(shiftElapsed)) {
				shiftElapsed = 0;
				List<Object[]> batch = bp.flushMobileBuffer();
				log("Koniec zmiany krwiobusu - wysylam partie " + batch.size() + " donacji.");
				for (Object[] entry : batch) {
					int    bId   = (Integer) entry[0];
					String bt    = (String)  entry[1];
					double dTime = (Double)  entry[2]; // oryginalny czas pobrania
					sendBloodCollected(bId, BloodPoints.UNIT_VOLUME, bt, dTime, true);
				}
			}
		}

		// Rezygnacja
		try {
			rtiamb.resignFederationExecution(ResignAction.DELETE_OBJECTS);
			log("Zrezygnowano z federacji.");
		} catch (RTIexception e) {
			log("Nie udalo sie poprawnie zrezygnowac z federacji: " + e.getMessage());
		}
//		try {
//			rtiamb.destroyFederationExecution(FEDERATION_NAME);
//			log("Federacja zniszczona.");
//		} catch (RTIexception e) {
//			log("Nie mozna zniszczyc federacji: " + e.getMessage());
//		}
	}

	// -----------------------------------------------------------------------
	// Wyslanie interakcji BloodCollected z wszystkimi parametrami z FOM
	// -----------------------------------------------------------------------
	private void sendBloodCollected(int bloodId, float bloodAmount, String bloodType,
									double donationTime, boolean isMobile) throws RTIexception {

		ParameterHandleValueMap params = rtiamb.getParameterHandleValueMapFactory().create(5);

		HLAinteger32BE encId = encoderFactory.createHLAinteger32BE(bloodId);
		params.put(bcBloodIdHandle, encId.toByteArray());

		HLAfloat32BE encAmount = encoderFactory.createHLAfloat32BE(bloodAmount);
		params.put(bcBloodAmountHandle, encAmount.toByteArray());

		HLAASCIIstring encType = encoderFactory.createHLAASCIIstring(bloodType);
		params.put(bcBloodTypeHandle, encType.toByteArray());

		HLAfloat64BE encTime = encoderFactory.createHLAfloat64BE(donationTime);
		params.put(bcDonationTimeHandle, encTime.toByteArray());

		// HLAboolean w Portico = HLAinteger32BE (0=false, 1=true)
		HLAinteger32BE encMobile = encoderFactory.createHLAinteger32BE(isMobile ? 1 : 0);
		params.put(bcIsMobileHandle, encMobile.toByteArray());

		rtiamb.sendInteraction(bloodCollectedHandle, params, generateTag());
		SimulationStats.recordDonation(isMobile);
		log("Wyslano BloodCollected: id=" + bloodId + ", type=" + bloodType
				+ ", amount=" + bloodAmount + ", donationTime=" + donationTime
				+ ", mobile=" + isMobile);
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
		// Publikuj BloodCollected
		bloodCollectedHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.BloodCollected");
		bcBloodIdHandle     = rtiamb.getParameterHandle(bloodCollectedHandle, "bloodId");
		bcBloodAmountHandle = rtiamb.getParameterHandle(bloodCollectedHandle, "bloodAmount");
		bcBloodTypeHandle   = rtiamb.getParameterHandle(bloodCollectedHandle, "bloodType");
		bcDonationTimeHandle= rtiamb.getParameterHandle(bloodCollectedHandle, "donationTime");
		bcIsMobileHandle    = rtiamb.getParameterHandle(bloodCollectedHandle, "isMobile");
		rtiamb.publishInteractionClass(bloodCollectedHandle);
		log("Publikuje: BloodCollected");
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
	public static void main(String[] args) {
		String federateName = (args.length > 0) ? args[0] : "BloodPoints";
		try {
			new BloodPointsFederate().runFederate(federateName);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public String getFederateName() {
		return federateName;
	}
}
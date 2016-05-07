package service;

import com.web.model.*;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.ModelAndView;
import repository.RecommendationsRepository;

import javax.servlet.http.HttpSession;
import java.util.*;

/**
 * Created by uday on 4/26/16.
 */
@Service("RecommendationsServiceImpl")
public class RecommendationsServiceImpl {
    private static final Logger logger = LoggerFactory.getLogger
            (RecommendationsServiceImpl.class);
    @Autowired RecommendationsRepository recommendationsRepository;
    @Autowired FitbitDetailsServiceIntf fitbitDetailsService;
    @Autowired TiSensorService tiSensorService;

    String userId;
    String todayDate;
    String access_token;
    Recommendations recommendations;
    HeartRateDetails heartRateDetails= null;
    FoodDetails foodDetails;
    ActivityDetails activityDetails;
    WaterDetails waterDetails = null ;
    ActivityGoalDetails activityGoalDetails;
    List<SynchronizedData> synchronizedSleepData;
    List<SynchronizedData> synchronizedTemperatureData;
    List<SynchronizedData> synchronizedLightData;
    List<SynchronizedData> synchronizedHumidityData;
    List<String> foodsConsumed;

    boolean lowTemperatureHasEffect = false;
    boolean highTemperatureHasEffect = false;
    boolean lightHasEffect = false;
    boolean lowHumidityHasEffect = false;
    boolean highHumidityHasEffect = false;
    boolean fairlyActive = false;

    static final String RESTLESS_SLEEP_VALUE = "2";
    static final String AWAKE_SLEEP_VALUE = "3";

    Recommendations defaultRecommendations;
    HashMap<String, List<String>> defaultTopics;
    HashMap<String, List<String>> topics;

    double idealTemperatureLow = 19;
    double idealTemperatureHigh = 25;
    double idealLightValue = 10;
    double idealHumidityLow = 45;
    double idealHumidityHigh = 55;
    double idealFairlyActiveMinutes = 25;
    String[] foodsInDB = {"coffee", "protein", "carbs"};
    static final int MAXIMUM_RECOMMENDATIONS_PER_TOPIC = 2;
    static final int MAXIMUM_FACTS = 3;

    public void setSessionVariables(HttpSession session){
        this.userId = session.getAttribute("userId").toString();
        this.access_token = session.getAttribute("access_token").toString();
        this.todayDate = session.getAttribute("todayDate").toString();
        fitbitDetailsService.setSessionVariables(session);
        tiSensorService.setSessionVariables(session);
    }

    public void addRecommendationsToModel(ModelAndView mv){
        recommendations = recommendationsRepository
                .getRecommendations(userId, todayDate);
        if(recommendations == null) {
            logger.info("No recommendations in database, building now!");
            calculateAndSaveRecommendations();
        }

        StringBuffer recommendationsForModel = new StringBuffer();
        if(recommendations == null){
            recommendationsForModel.append("[As data that you have provided " +
                    "is very limited, There are no recommendations for now!]");
        }else{
            recommendationsForModel.append("[");
            try {
                Iterator it = recommendations.getTopics().entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry entry = (Map.Entry) it.next();
                    List<String> topic = (List<String>) entry.getValue();
                    for (int i = 0; i < topic.size(); i++) {
                        recommendationsForModel.append(topic.get(i) + ", ");
                    }
                }
                if (recommendationsForModel.length() > 1) {
                    recommendationsForModel.setLength(recommendationsForModel
                            .length() - 2);
                }
            }catch (Exception e){
                logger.error("Unable to add recommendations to model "+e);
            }
            recommendationsForModel.append("]");
        }
        mv.addObject("recommendations", recommendationsForModel);

        StringBuffer recommendationFactsForModel = new StringBuffer();
        recommendationFactsForModel.append("[");
        try {
            List<String> facts = defaultRecommendations
                    .getTopics().get("facts");
            int[] randomNumbers = getRandomNumbers(facts.size());
            for(int i=0; i<MAXIMUM_FACTS; i++){
                recommendationFactsForModel.append(facts.get(i)+", ");
            }
            if(recommendationFactsForModel.length() > 1){
                recommendationFactsForModel.setLength
                        (recommendationFactsForModel.length()-2);
            }
        }catch(Exception e){
            logger.error("Unable to get facts for the model"+e);
        }
        recommendationFactsForModel.append("]");
        mv.addObject("recommendationFacts", recommendationFactsForModel);
    }

    private void calculateAndSaveRecommendations(){
        getDataFromDB();
        try{
            classifyFood();
            classifyActivity();
            calculateEffectsOnDisturbedSleepTimeFrames();
            calculateRecommendations();
        }catch (Exception e){
            logger.error("Error in generating recommendations"+e.getMessage());
        }
        saveRecommendations();
    }

    private void saveRecommendations(){
        try {
            recommendationsRepository.saveRecommendations(recommendations);
        }catch (Exception e){
            logger.error("Error in saving recommendations to database"+e.getStackTrace());
        }
    }

    private void classifyFood(){
        if(foodDetails == null || foodDetails.getFoods() == null ||
                foodDetails.getSummary() == null){
            return;
        }
        foodsConsumed = new ArrayList<String>();
        try {
            for(int i=0; i<foodDetails.getFoods().length; i++){
                FoodDetails.Foods food = foodDetails.getFoods()[i];
                String items;
                items = food.getLoggedFood().getName();
                for(int j=0; j<foodsInDB.length; j++){
                    if(items.contains(foodsInDB[j])){
                        foodsConsumed.add(foodsInDB[j]);
                    }
                }
            }
            FoodDetails.Summary foodSummary = foodDetails.getSummary();

            parseAndAddFood("carbs", foodSummary.getCarbs());
            parseAndAddFood("protein", foodSummary.getProtein());
            parseAndAddFood("fat", foodSummary.getFat());
            parseAndAddFood("fiber", foodSummary.getFiber());
            parseAndAddFood("water", foodSummary.getWater());
        }catch (Exception e){
            logger.error("Unable to parse food data" +
                    ExceptionUtils.getFullStackTrace(e));
        }
    }

    private void parseAndAddFood(String food, String value){
        Double foodConsumptionValue = Double.parseDouble(value);
        if(foodConsumptionValue > 0)
            foodsConsumed.add(food);
    }

    private void classifyActivity(){
        try {
            Double fairlyActiveMinutes = Double.parseDouble(
                    activityDetails.getSummary()
                            .getFairlyActiveMinutes());
            if(fairlyActiveMinutes > idealFairlyActiveMinutes){
                fairlyActive = true;
            }
        }catch(Exception e) {
            logger.error("Unable to parse activity data for recommendations"+e);
        }
    }

    private void calculateRecommendations(){
        recommendations = new Recommendations();
        defaultTopics = defaultRecommendations.getTopics();
        topics = new HashMap<String, List<String>>();
        recommendations.setTopics(topics);
        recommendations.setUserId(userId);
        recommendations.setDate(todayDate);
        setTemperatureRecommendations();
        setLightRecommendations();
        setHumidityRecommendations();
        setFoodRecommendations();
        setActivityRecommendations();
    }

    private void setActivityRecommendations(){
        if(fairlyActive)
            setTopicRecommendations("activity");
    }

    private void setFoodRecommendations(){
        String key = null;
        if(foodsConsumed == null || foodsConsumed.size() == 0){
            for(int i=0; i<foodsConsumed.size(); i++) {
                setTopicRecommendations("food_" + foodsConsumed.get(i));
            }
        }
    }

    private void setHumidityRecommendations(){
        String key = null;
        if(lowHumidityHasEffect) key = "humidity_low";
        else if(highHumidityHasEffect) key = "humidity_high";
        else return;
        setTopicRecommendations(key);
    }

    private void setLightRecommendations(){
        if(lightHasEffect) setTopicRecommendations("light");
        return;
    }

    private void setTemperatureRecommendations(){
        String key = null;
        if(lowTemperatureHasEffect) key = "temperature_low";
        else if(highTemperatureHasEffect) key = "temperature_high";
        else return;
        setTopicRecommendations(key);
    }

    private void setTopicRecommendations(String key){
        List<String> defaultTopicRecommendations;
        if(defaultTopics.containsKey(key))
            defaultTopicRecommendations = defaultTopics.get(key);
        else{
            logger.warn("No recommendations found for topic "+key);
            return;
        }
        List<String> topicRecommendations = new ArrayList<String>();
        int[] arr = getRandomNumbers(defaultTopicRecommendations.size());

        for(int i=0; i<MAXIMUM_RECOMMENDATIONS_PER_TOPIC; i++){
            topicRecommendations.add(defaultTopicRecommendations.get(arr[i]));
        }
        logger.debug("Recommendations set for topic: "+key+" are "+
                topicRecommendations);
        topics.put(key, topicRecommendations);
    }

    private int[] getRandomNumbers(int sizeOfTopic){
        int[] randomNumbers = new int[sizeOfTopic];
        for(int i = 0; i<sizeOfTopic; i++){
            randomNumbers[i] = i;
        }
        Collections.shuffle(Arrays.asList(randomNumbers));
        return randomNumbers;
    }

    private void calculateEffectsOnDisturbedSleepTimeFrames(){
        for(int i=0; i<synchronizedSleepData.size(); i++){
            SynchronizedData sdmS = synchronizedSleepData.get(i);
            if(sdmS.getValue() != null && (sdmS.getValue().equals
                    (RESTLESS_SLEEP_VALUE) ||
                    sdmS.getValue().equals(AWAKE_SLEEP_VALUE))){
                if(!(lowTemperatureHasEffect || highTemperatureHasEffect)) {
                    checkTemperaturesEffect(
                            synchronizedTemperatureData.get(i).getValue());
                }
                if(!lightHasEffect) {
                    checkLightEffect(synchronizedLightData.get(i).getValue());
                }
                if(!(lowHumidityHasEffect || highHumidityHasEffect)) {
                    checkHumidityEffect(synchronizedHumidityData.get(i).getValue());
                }
            }
        }
    }

    private void checkTemperaturesEffect(String temperature){
        if(temperature == null){
            return;
        }
        double temp = Double.parseDouble(temperature);
        if(temp < idealTemperatureLow){
            lowTemperatureHasEffect = true;
            return;
        }
        if(temp > idealTemperatureHigh){
            highTemperatureHasEffect = true;
        }
    }

    private void checkLightEffect(String light){
        if(light == null){
            return;
        }
        Double lightValue = Double.parseDouble(light);
        if(lightValue > idealLightValue){
            lightHasEffect = true;
        }
    }

    private void checkHumidityEffect(String humidity){
        if(humidity == null){
            return;
        }
        Double humidityValue = Double.parseDouble(humidity);
        if(humidityValue < idealHumidityLow){
            lowHumidityHasEffect = true;
            return;
        }
        if(humidityValue > idealHumidityHigh){
            highHumidityHasEffect = true;
        }
    }

    private void getDataFromDB(){
        try {
            synchronizedSleepData = fitbitDetailsService.getSleepInRequiredFormat();
            heartRateDetails = fitbitDetailsService.getHeartRateDetailsFromDB();
            foodDetails = fitbitDetailsService.getFoodDetailsFromDB();
            activityDetails = fitbitDetailsService.getActivityDetailsFromDB();
            waterDetails = fitbitDetailsService.getWaterDetailsFromDB();
            activityGoalDetails = fitbitDetailsService.getActivityGoalDetailsFromDB();
            synchronizedTemperatureData = tiSensorService
                    .getTemperatureInRequiredFormat();
            synchronizedLightData = tiSensorService
                    .getLightInRequiredFormat();
            synchronizedHumidityData = tiSensorService
                    .getHumidityInRequiredFormat();
            getDefaultRecommendations();
        }catch (Exception e){
            logger.error("Error in getting data for generating " +
                    "recommendations " + e.getStackTrace());
        }
    }

    private void getDefaultRecommendations(){
        defaultRecommendations = recommendationsRepository.getRecommendations
                ("default", "all");
    }
    public void removeRecommendationsFromDB(){
    	recommendationsRepository.removeRecommendations(userId,todayDate);
    }
}

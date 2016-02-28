package service;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.ModelAndView;

import com.web.model.UserProfile;
import com.web.utils.Constants;

import repository.UserProfileRepositoryIntf;

@Service("UserProfileService")
public class UserProfileServiceImpl implements UserProfileServiceIntf{
	String controllerMessage = "In Fitbit-OAuth-Connection Controller";
	String redirectUrl;
	ModelAndView mv;
	String url;
	String urlParameters;
	HttpsURLConnection con;
	URL urlobj; 
	DataOutputStream dataOutputStream; 
	BufferedReader br;
	JSONObject jsonObject;
	
	UserProfileRepositoryIntf repository;
	
	public UserProfileServiceImpl()
	{
		
	}
	
	@Autowired
	public UserProfileServiceImpl(UserProfileRepositoryIntf repository)
	{
		this.repository = repository;
	}
	
	public UserProfile getUserProfileDetails(String accessToken,String refreshToken) throws IOException
	{	
		url = Constants.getUserProfileURL();
		urlobj = new URL(url);

		con = (HttpsURLConnection) urlobj.openConnection();

		//add request header
		con.setRequestMethod("GET");
		con.setRequestProperty("Authorization", "Bearer "+accessToken);

		int responseCode = con.getResponseCode();
		System.out.println("\nSending 'GET' request to URL : " + url);
		System.out.println("Response Code : " + responseCode);

		//read the data
		br = new BufferedReader(
				new InputStreamReader(con.getInputStream()));

		String userProfileData = "";

		String inputLine;
		while ((inputLine = br.readLine()) != null) {
			userProfileData=userProfileData+inputLine+"\n";
		}

		System.out.print(userProfileData);

		br.close();
		

		//parse json
		jsonObject = new JSONObject(userProfileData);		
		JSONObject innerJsonObject = new JSONObject(jsonObject.get("user").toString());

		
		UserProfile userProfile = new UserProfile(
				innerJsonObject.get("age").toString()
				,innerJsonObject.get("avatar").toString()
				,innerJsonObject.get("gender").toString()
				,innerJsonObject.get("dateOfBirth").toString()
				,innerJsonObject.get("fullName").toString()
		);
		
		
		//TODO: check if user already present, If not create, else just find the user
		repository.createUser(userProfile);
		
		
		return userProfile;
	}
}

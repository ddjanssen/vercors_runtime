package hre.config;

import static hre.System.*;

public class IntegerSetting {

  private int value;
  
  public IntegerSetting(int default_setting){
    value=default_setting;
  }
  
  private class AssignOption extends AbstractOption {
    IntegerSetting about;
    public AssignOption(IntegerSetting about,String help){
      super(true,help);
      this.about=about;
    }
    public void pass(String value){
      used=true;
      about.value=Integer.parseInt(value);
    }
  }
  
  public Option getAssign(String help){
    return new AssignOption(this,help);
  }
  public int get(){
    return value;
  }
  
  public void set(int value){
    this.value=value;
  }
}

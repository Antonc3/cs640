package edu.wisc.cs.sdn.vnet.sw;

import java.util.concurrent.ConcurrentHashMap;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	
	final long TTL = 15*1000;
	class SwitchEntry{
		long lastReceived;
		Iface iface;
		MACAddress mac;
		public SwitchEntry(MACAddress from, Iface inIface){
			mac = from;
			iface = inIface;
			lastReceived =System.currentTimeMillis();
		}
		public void resetTime(){
			lastReceived =System.currentTimeMillis();
		}
		public long getTime(){
			return lastReceived;
		}
		public Iface getIface(){
			return iface;
		}
		public void setIface(Iface inIface){
			iface = inIface;
		}
		public MACAddress getMAC(){
			return mac;
		}
	}
	ConcurrentHashMap<MACAddress, SwitchEntry> mp ;
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		mp = new ConcurrentHashMap<MACAddress,SwitchEntry>();
		new Thread(()->{
			while (true){
				try{
					this.removeInvalidEntries();
					Thread.sleep(1000);
				}
				catch(Exception e){

				}
			}
		}).start();
	}

	public void removeInvalidEntries(){
		for(SwitchEntry se : mp.values()){
			if(System.currentTimeMillis()-se.getTime()>= TTL){
				mp.remove(se.getMAC());
			}
		}
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		/* TODO: Handle packets                                             */


		MACAddress from = etherPacket.getSourceMAC();
		MACAddress dst = etherPacket.getDestinationMAC();

		if(!mp.containsKey(from)){
			mp.put(from,new SwitchEntry(from,inIface));
		}
		else{
			mp.get(from).resetTime();
			mp.get(from).setIface(inIface);
		}
		

		if(mp.containsKey(dst)){ 
			sendPacket(etherPacket,mp.get(dst).getIface());
			return;
		}
		else{
			for(Iface iface : this.interfaces.values()){
				if(iface.equals(inIface)) continue;
				sendPacket(etherPacket,iface);
			}
		}

		/********************************************************************/
	}
}

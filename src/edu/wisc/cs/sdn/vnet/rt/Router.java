package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;

import java.util.HashMap;

import net.floodlightcontroller.packet.Ethernet;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	public static final boolean FAIL_ON_NULL_MAC = true;
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;

	//private HashMap<Integer,RIPv2Entry> ripTable;
	private RIP ripHandler;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		//this.ripTable = new HashMap<Integer,RIPv2Entry>();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	public void enableRIP(){
		this.routeTable.enableRIP = true;
		this.ripHandler = new RIP(this);
	}

	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
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
		

		System.out.println("Packet type: " + etherPacket.getEtherType());
		System.out.println("IPV4 type: " + Ethernet.TYPE_IPv4);
		if(etherPacket.getEtherType() == Ethernet.TYPE_IPv4){
			System.out.println("Found Ether Packet");
			this.handleIpPacket(etherPacket,inIface);
			return;
		}
		System.out.println("Dropping packet!");
	}

	public void handleIpPacket(Ethernet etherPacket, Iface inIface){
		IPv4 ipPacket = (IPv4) etherPacket.getPayload();

		int dest = ipPacket.getDestinationAddress();
		System.out.println("DEST: "+ dest);

		short checksum = ipPacket.getChecksum();
		ipPacket.resetChecksum();
		byte [] serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		short curChecksum = ipPacket.getChecksum();
		if(checksum != curChecksum){
			System.out.println("Checksum is bad");
			return;
		}

		ipPacket.setTtl((byte)(ipPacket.getTtl()-1));
		if(ipPacket.getTtl() == 0){
			System.out.println("TTL is 0");
		       	return;
		}


		ipPacket.resetChecksum();

		if (this.routeTable.enableRIP && ripHandler.isRipPacket(ipPacket)) {
			ripHandler.handlePacket(etherPacket, inIface);
			return;
		}

		if(ipPacket.getProtocol() == IPv4.PROTOCOL_UDP 
				&& ((UDP)ipPacket.getPayload()).getDestinationPort() == UDP.RIP_PORT){
			System.out.println("RIP packet");
			return;
		}
		etherPacket.setPayload(ipPacket);

		
		for(Iface iface : this.interfaces.values()){
			System.out.println(iface.getIpAddress());
			if(iface.getIpAddress() == dest){
				System.out.println("Found dest");
				return;
			}
		}
		//send pacekt

		System.out.println("LOOKING UP DEST: " + dest);
		RouteEntry best = this.routeTable.lookup(dest);
		if(best==null){
			System.out.println("best is null");
			return;
		}

		System.out.println("BEST dst address: " + best.getDestinationAddress());
		Iface outIface = best.getInterface();

		if(outIface.getMacAddress()==null){
			if(FAIL_ON_NULL_MAC){
				throw new NullPointerException("outiface " + outIface.getName() + " has a null macaddress");
			}
			System.out.println("outiface " + outIface.getName() + " has a null macaddress");
			
			return;
		}
		else{
			System.out.println("outiface" + outIface.getMacAddress().toBytes().toString());
			etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
		}

		int nxtHop = best.getGatewayAddress();
		if(nxtHop == 0){
			nxtHop = dest;
		}
		ArpEntry arpEntry  = this.arpCache.lookup(nxtHop);
		if(arpEntry==null){
			System.out.println("arp etnry is null");
			return;
		}
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());

		this.sendPacket(etherPacket,outIface);

	}
}

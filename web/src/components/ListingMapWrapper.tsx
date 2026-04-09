"use client";

import dynamic from "next/dynamic";

const ListingMap = dynamic(() => import("@/components/ListingMap"), { ssr: false });

interface Props {
  lat: number;
  lng: number;
  label?: string;
}

export default function ListingMapWrapper({ lat, lng, label }: Props) {
  return <ListingMap lat={lat} lng={lng} label={label} />;
}

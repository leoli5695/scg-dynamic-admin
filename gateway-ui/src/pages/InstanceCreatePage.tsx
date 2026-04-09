import React, { useState, useEffect } from "react";
import {
  Card,
  Form,
  Input,
  Select,
  InputNumber,
  Button,
  Space,
  message,
  Spin,
  Tag,
  Tooltip,
  Alert,
} from "antd";
import {
  ClusterOutlined,
  ArrowLeftOutlined,
  CloudServerOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import axios from "axios";

const { Option } = Select;
const { TextArea } = Input;

interface KubernetesCluster {
  id: number;
  clusterName: string;
  connectionStatus: string;
  clusterVersion: string;
}

interface InstanceSpec {
  type: string;
  cpuCores: number;
  memoryMB: number;
  description: string;
}

interface LocalImage {
  name: string;
  namespace?: string;
  image?: string;
  tag?: string;
}

const InstanceCreatePage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [clusters, setClusters] = useState<KubernetesCluster[]>([]);
  const [specs, setSpecs] = useState<InstanceSpec[]>([]);

  // Watch form fields
  const selectedSpecType = Form.useWatch("specType", form);
  const selectedClusterId = Form.useWatch("clusterId", form);
  const selectedImagePullPolicy = Form.useWatch("imagePullPolicy", form);

  // Local image state
  const [localImages, setLocalImages] = useState<LocalImage[]>([]);
  const [loadingImages, setLoadingImages] = useState(false);
  const [selectedImageNamespace, setSelectedImageNamespace] = useState<string | undefined>(undefined);

  useEffect(() => {
    fetchData();
  }, []);

  // Load local images when cluster is selected and pull policy is "Never"
  useEffect(() => {
    if (selectedClusterId && selectedImagePullPolicy === "Never") {
      loadLocalImages(selectedClusterId);
    } else {
      setLocalImages([]);
      setSelectedImageNamespace(undefined);
    }
  }, [selectedClusterId, selectedImagePullPolicy]);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [clustersRes, specsRes] = await Promise.all([
        axios.get("/api/kubernetes/clusters"),
        axios.get("/api/instances/specs"),
      ]);
      setClusters(clustersRes.data.data || []);
      setSpecs(specsRes.data.data || []);
    } catch (error) {
      console.error("Failed to fetch data:", error);
      message.error(t("common.fetch_error"));
    } finally {
      setLoading(false);
    }
  };

  const loadLocalImages = async (clusterId: number) => {
    setLoadingImages(true);
    try {
      const res = await axios.get(`/api/kubernetes/clusters/${clusterId}/images`);
      if (res.data.code === 200) {
        const rawImages = res.data.data || [];
        // Parse image names into structured format
        const parsed = rawImages.map((img: any) => {
          const fullName = img.name;
          // Parse image name: [registry/][namespace/]image[:tag]
          const parts = fullName.split('/');
          let namespace = '';
          let imageWithTag = fullName;
          
          if (parts.length >= 2 && !parts[0].includes(':')) {
            // Has namespace
            namespace = parts[0];
            imageWithTag = parts.slice(1).join('/');
          }
          
          const [image, tag] = imageWithTag.split(':');
          
          return {
            name: fullName,
            namespace: namespace || 'default',
            image: image,
            tag: tag || 'latest'
          };
        });
        
        setLocalImages(parsed);
        
        // Auto-select k8s.io namespace if available
        const k8sImages = parsed.filter((img: { namespace: string }) => img.namespace === 'k8s.io');
        if (k8sImages.length > 0) {
          setSelectedImageNamespace('k8s.io');
        }
      }
    } catch (error) {
      console.error("Failed to load local images:", error);
      message.warning("Failed to load local images from cluster");
      setLocalImages([]);
    } finally {
      setLoadingImages(false);
    }
  };

  // Filter images by namespace
  const filteredImages = selectedImageNamespace 
    ? localImages.filter((img: LocalImage) => img.namespace === selectedImageNamespace)
    : localImages;

  // Get unique namespaces
  const namespaces = Array.from(new Set(localImages.map((img: LocalImage) => img.namespace || 'default')));

  const handleSubmit = async (values: any) => {
    setSubmitting(true);
    try {
      const response = await axios.post("/api/instances", values);
      if (response.data.code === 200) {
        message.success(t("instance.create_success"));
        navigate("/instances");
      } else {
        message.error(response.data.message || t("instance.create_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("instance.create_failed"));
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    navigate("/instances");
  };

  return (
    <div className="instance-create-page" style={{ padding: "24px", maxWidth: "800px", margin: "0 auto" }}>
      <Card
        title={
          <Space>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={handleCancel}
            />
            <CloudServerOutlined />
            {t("instance.create_instance")}
          </Space>
        }
        styles={{
          header: { borderBottom: "1px solid rgba(255,255,255,0.1)" },
          body: { padding: "24px" },
        }}
        style={{
          background: "rgba(30, 41, 59, 0.95)",
          borderRadius: "12px",
        }}
      >
        <Spin spinning={loading}>
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSubmit}
            initialValues={{
              specType: "medium",
              replicas: 1,
              createNamespace: true,
              image: "my-gateway:latest",
              imagePullPolicy: "IfNotPresent",
            }}
          >
            <Form.Item
              name="instanceName"
              label={t("instance.instance_name")}
              rules={[{ required: true, message: t("common.required") }]}
            >
              <Input placeholder={t("instance.instance_name_placeholder")} />
            </Form.Item>

            <Form.Item
              name="clusterId"
              label={t("instance.cluster")}
              rules={[{ required: true, message: t("common.required") }]}
            >
              <Select placeholder={t("instance.select_cluster")}>
                {clusters
                  .filter((c) => c.connectionStatus === "CONNECTED")
                  .map((cluster) => (
                    <Option key={cluster.id} value={cluster.id}>
                      <Space>
                        <ClusterOutlined />
                        {cluster.clusterName}
                        <Tag color="blue">{cluster.clusterVersion}</Tag>
                      </Space>
                    </Option>
                  ))}
              </Select>
            </Form.Item>

            <Form.Item
              name="namespace"
              label={t("instance.namespace")}
              rules={[{ required: true, message: t("common.required") }]}
            >
              <Input placeholder={t("instance.namespace_placeholder")} />
            </Form.Item>

            <Form.Item name="createNamespace" label={t("instance.create_namespace")}>
              <Select>
                <Option value={true}>{t("common.yes")}</Option>
                <Option value={false}>{t("common.no")}</Option>
              </Select>
            </Form.Item>

            {/* Nacos & Redis Configuration */}
            <Alert
              message={t("instance.config_hint")}
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
            />
            <Form.Item
              name="nacosServerAddr"
              label={t("instance.nacos_server_addr")}
              tooltip={t("instance.nacos_server_addr_tooltip")}
            >
              <Input placeholder={t("instance.nacos_server_addr_placeholder")} />
            </Form.Item>

            <Form.Item
              name="redisServerAddr"
              label={t("instance.redis_server_addr")}
              tooltip={t("instance.redis_server_addr_tooltip")}
            >
              <Input placeholder={t("instance.redis_server_addr_placeholder")} />
            </Form.Item>

            <Form.Item
              name="specType"
              label={t("instance.spec_type")}
              rules={[{ required: true }]}
            >
              <Select>
                {specs.map((spec) => (
                  <Option key={spec.type} value={spec.type}>
                    {spec.type === "custom"
                      ? t("instance.spec_custom")
                      : t(`instance.spec_${spec.type}`)}
                  </Option>
                ))}
              </Select>
            </Form.Item>

            {selectedSpecType === "custom" && (
              <Space style={{ width: "100%" }} size="large">
                <Form.Item
                  name="cpuCores"
                  label={t("instance.cpu_cores")}
                  rules={[{ required: true, message: t("common.required") }]}
                  style={{ width: "200px", marginBottom: 0 }}
                >
                  <InputNumber
                    min={0.1}
                    max={16}
                    step={0.5}
                    style={{ width: "100%" }}
                    placeholder="1.0"
                  />
                </Form.Item>
                <Form.Item
                  name="memoryMB"
                  label={t("instance.memory_mb")}
                  rules={[{ required: true, message: t("common.required") }]}
                  style={{ width: "200px", marginBottom: 0 }}
                >
                  <InputNumber
                    min={128}
                    max={16384}
                    step={128}
                    style={{ width: "100%" }}
                    placeholder="1024"
                  />
                </Form.Item>
              </Space>
            )}

            <Form.Item
              name="replicas"
              label={t("instance.replicas")}
              rules={[{ required: true }]}
              style={{ marginBottom: 24 }}
            >
              <InputNumber min={1} max={10} style={{ width: "200px" }} />
            </Form.Item>

            <Form.Item
              name="imagePullPolicy"
              label={t("instance.image_pull_policy")}
              tooltip={t("instance.image_pull_policy_tooltip")}
              style={{ marginBottom: 24 }}
            >
              <Select style={{ width: "100%" }}>
                <Option value="IfNotPresent">{t("instance.pull_if_not_present")}</Option>
                <Option value="Always">{t("instance.pull_always")}</Option>
                <Option value="Never">{t("instance.pull_never")}</Option>
              </Select>
            </Form.Item>

            <Form.Item
              name="image"
              label={t("instance.image")}
              tooltip={
                selectedImagePullPolicy === "Never" 
                  ? "Select from local images available on cluster nodes (k8s.io namespace shown by default)"
                  : t("instance.image_tooltip")
              }
              style={{ marginBottom: 24 }}
            >
              {selectedImagePullPolicy === "Never" ? (
                loadingImages ? (
                  <Spin tip="Loading images from cluster..." />
                ) : localImages.length > 0 ? (
                  <Space direction="vertical" style={{ width: "100%" }} size="small">
                    {/* Namespace Filter */}
                    <Select
                      value={selectedImageNamespace || 'all'}
                      onChange={setSelectedImageNamespace}
                      style={{ width: "100%" }}
                      placeholder="Filter by namespace"
                    >
                      <Option value="all">All Namespaces ({localImages.length})</Option>
                      {namespaces.map(ns => {
                        const count = localImages.filter((img: LocalImage) => img.namespace === ns).length;
                        return (
                          <Option key={ns} value={ns}>
                            {ns} ({count})
                          </Option>
                        );
                      })}
                    </Select>
                    
                    {/* Image Selector */}
                    <Select
                      showSearch
                      placeholder={selectedImageNamespace ? t('instance.select_image_from_namespace', { ns: selectedImageNamespace }) : t('instance.select_local_image')}
                      optionFilterProp="children"
                      loading={loadingImages}
                      allowClear
                      style={{ width: "100%" }}
                      optionLabelProp="title"
                    >
                      {filteredImages.map((img, idx) => (
                        <Option key={idx} value={img.name} title={img.name}>
                          <Space style={{ minWidth: 0, flex: 1 }}>
                            <Tag color="blue">{img.namespace}</Tag>
                            <span style={{ 
                              fontSize: '13px', 
                              color: '#f1f5f9',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                              maxWidth: '300px',
                              flex: '1 1 auto'
                            }} title={img.name}>
                              {img.image}
                            </span>
                            <Tag color="green">{img.tag}</Tag>
                          </Space>
                        </Option>
                      ))}
                    </Select>
                  </Space>
                ) : (
                  <Input 
                    placeholder="No local images found in cluster. Enter image name manually." 
                    style={{ width: "100%" }} 
                  />
                )
              ) : (
                <Input 
                  placeholder="e.g., my-gateway:latest" 
                  style={{ width: "100%" }} 
                />
              )}
            </Form.Item>

            <Form.Item name="description" label={t("instance.description")}>
              <TextArea rows={3} />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0, marginTop: "24px" }}>
              <Space>
                <Button onClick={handleCancel}>{t("common.cancel")}</Button>
                <Button type="primary" htmlType="submit" loading={submitting}>
                  {t("instance.create_and_deploy")}
                </Button>
              </Space>
            </Form.Item>
          </Form>
        </Spin>
      </Card>
    </div>
  );
};

export default InstanceCreatePage;
